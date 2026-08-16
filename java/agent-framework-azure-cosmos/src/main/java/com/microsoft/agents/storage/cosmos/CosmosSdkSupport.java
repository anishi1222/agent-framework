// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.FeedResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

final class CosmosSdkSupport {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CosmosSdkSupport() {}

    static String partitionKey(CosmosPartitionContext context, String kind, String logicalKey) {
        return identifier("pk", kind, context.tenantId(), context.isolationId(), context.agentId(), logicalKey);
    }

    static String itemId(String kind, String... parts) {
        String[] values = new String[parts.length + 1];
        values[0] = kind;
        System.arraycopy(parts, 0, values, 1, parts.length);
        return identifier("id", values);
    }

    static String encodePayload(byte[] payload, int maxDocumentBytes) {
        Objects.requireNonNull(payload, "payload");
        String encoded = Base64.getEncoder().encodeToString(payload);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > maxDocumentBytes) {
            throw new ValidationException(
                    "Encoded Cosmos state exceeds configured maxDocumentBytes " + maxDocumentBytes + ".");
        }
        return encoded;
    }

    static String payloadDigest(byte[] payload) {
        return URL_ENCODER.encodeToString(sha256().digest(Objects.requireNonNull(payload, "payload")));
    }

    static byte[] decodePayload(Object payload, int maxDocumentBytes) {
        if (!(payload instanceof String encoded)) {
            throw new CosmosStorageException(
                    "Stored Cosmos document has an invalid payload.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
        try {
            if (encoded.getBytes(StandardCharsets.UTF_8).length > maxDocumentBytes) {
                throw new CosmosStorageException(
                        "Stored Cosmos payload exceeds configured maxDocumentBytes.",
                        null,
                        CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                        null);
            }
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new CosmosStorageException(
                    "Stored Cosmos document has an invalid payload encoding.",
                    exception,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
    }

    static <T> CompletionStage<T> stage(Mono<T> mono, CosmosRetryOptions options, RunCancellation cancellation) {
        Objects.requireNonNull(mono, "mono");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedStage(new RunCancelledException());
        }
        CompletableFuture<T> upstream = mono.timeout(options.operationTimeout()).toFuture();
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<RunCancellationRegistration> registration = new AtomicReference<>();
        registration.set(RunCancellations.register(cancellation, () -> {
            upstream.cancel(true);
            result.completeExceptionally(new RunCancelledException());
        }));
        upstream.whenComplete((value, failure) -> {
            RunCancellationRegistration current = registration.getAndSet(null);
            if (current != null) {
                current.close();
            }
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(mapFailure(failure));
            }
        });
        return result.minimalCompletionStage();
    }

    static <T> CompletionStage<T> stage(Mono<T> mono, CosmosRetryOptions options) {
        return stage(mono, options, new DefaultRunCancellation());
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static boolean hasStatus(Throwable failure, int status) {
        Throwable cause = unwrap(failure);
        if (cause instanceof CosmosException cosmos) {
            return cosmos.getStatusCode() == status;
        }
        return cause instanceof CosmosStorageException mapped
                && mapped.diagnostics() != null
                && mapped.diagnostics().statusCode() != null
                && mapped.diagnostics().statusCode() == status;
    }

    static StorageConflictException conflict(String subject, long expected, Long actual) {
        return new StorageConflictException(subject + " expected revision " + expected + " but current revision is "
                + (actual == null ? "absent" : actual) + ".");
    }

    static CosmosOperationDiagnostics diagnostics(CosmosException exception) {
        Double charge = exception.getRequestCharge() < 0 ? null : exception.getRequestCharge();
        return new CosmosOperationDiagnostics(
                exception.getStatusCode(),
                blankToNull(exception.getActivityId()),
                charge,
                positiveOrNull(exception.getRetryAfterDuration()));
    }

    static CosmosOperationDiagnostics diagnostics(FeedResponse<?> response) {
        return new CosmosOperationDiagnostics(
                200, blankToNull(response.getActivityId()), response.getRequestCharge(), null);
    }

    static RuntimeException mapFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof CosmosStorageException mapped) {
            return mapped;
        }
        if (cause instanceof StorageConflictException conflict) {
            return conflict;
        }
        if (cause instanceof TimeoutException) {
            return new CosmosStorageException(
                    "Cosmos DB operation exceeded its configured deadline.",
                    cause,
                    CosmosStorageException.Kind.TIMEOUT,
                    null);
        }
        if (cause instanceof CosmosException cosmos) {
            CosmosOperationDiagnostics diagnostics = diagnostics(cosmos);
            if (cosmos.getStatusCode() == 429) {
                return new CosmosThrottledException(cosmos, diagnostics);
            }
            CosmosStorageException.Kind kind =
                    switch (cosmos.getStatusCode()) {
                        case 401, 403 -> CosmosStorageException.Kind.AUTHENTICATION;
                        case 404 -> CosmosStorageException.Kind.NOT_FOUND;
                        default -> CosmosStorageException.Kind.SERVICE;
                    };
            return new CosmosStorageException(
                    "Cosmos DB operation failed with status " + cosmos.getStatusCode() + ".",
                    cosmos,
                    kind,
                    diagnostics);
        }
        return new CosmosStorageException(
                "Cosmos DB transport failed.", cause, CosmosStorageException.Kind.TRANSPORT, null);
    }

    private static String identifier(String prefix, String... values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            String checked = CosmosValidation.requireNonBlank(value, "identifier component");
            byte[] bytes = checked.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 4096) {
                throw new ValidationException("Cosmos identifier components must not exceed 4096 UTF-8 bytes.");
            }
            digest.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return "af1-" + prefix + "-" + URL_ENCODER.encodeToString(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Duration positiveOrNull(Duration value) {
        return value == null || value.isNegative() || value.isZero() ? null : value;
    }
}
