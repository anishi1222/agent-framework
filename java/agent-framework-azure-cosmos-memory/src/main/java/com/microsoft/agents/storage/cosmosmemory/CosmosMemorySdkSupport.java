// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.FeedResponse;
import com.microsoft.agents.agents.memory.MemoryRecord;
import com.microsoft.agents.agents.memory.MemoryScope;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.storage.cosmos.CosmosOperationDiagnostics;
import com.microsoft.agents.storage.cosmos.CosmosRetryOptions;
import com.microsoft.agents.storage.cosmos.CosmosStorageException;
import com.microsoft.agents.storage.cosmos.CosmosThrottledException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

final class CosmosMemorySdkSupport {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private CosmosMemorySdkSupport() {}

    static void validateScope(CosmosMemoryOptions options, MemoryScope scope) {
        if (scope == null) {
            throw new ValidationException("scope must not be null.");
        }
        if (!options.storage().partition().tenantId().equals(scope.tenantId())) {
            throw new ValidationException("Memory scope tenantId must match the store tenant boundary.");
        }
    }

    static String partitionKey(CosmosMemoryOptions options, MemoryScope scope) {
        validateScope(options, scope);
        return identifier(
                "pk",
                "memory",
                options.storage().partition().tenantId(),
                options.storage().partition().isolationId(),
                options.storage().partition().agentId(),
                scope.scopeId());
    }

    static String itemId(CosmosMemoryOptions options, MemoryRecord record) {
        return itemId(options, record.key().scope(), record.key().memoryId());
    }

    static String itemId(CosmosMemoryOptions options, MemoryScope scope, String memoryId) {
        validateScope(options, scope);
        return identifier(
                "id",
                "memory",
                options.storage().partition().agentId(),
                scope.scopeId(),
                requireNonBlank(memoryId, "memoryId"));
    }

    static String tenantDigest(CosmosMemoryOptions options) {
        return identifier("tenant", options.storage().partition().tenantId());
    }

    static String scopeDigest(MemoryScope scope) {
        return identifier("scope", scope.tenantId(), scope.scopeId());
    }

    static String recordDigest(MemoryRecord record) {
        MessageDigest digest = sha256();
        update(digest, record.key().scope().tenantId());
        update(digest, record.key().scope().scopeId());
        update(digest, record.key().memoryId());
        update(digest, record.content());
        record.metadata().values().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    update(digest, entry.getKey());
                    updateState(digest, entry.getValue());
                });
        if (record.embedding() == null) {
            digest.update((byte) 0);
        } else {
            digest.update((byte) 1);
            record.embedding()
                    .values()
                    .forEach(value -> digest.update(ByteBuffer.allocate(Long.BYTES)
                            .putLong(Double.doubleToLongBits(value))
                            .array()));
        }
        update(digest, record.createdAt().toString());
        update(digest, record.updatedAt().toString());
        update(
                digest,
                record.timeToLiveSeconds() == null
                        ? ""
                        : record.timeToLiveSeconds().toString());
        return ENCODER.encodeToString(digest.digest());
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
                    "Cosmos memory operation exceeded its configured deadline.",
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
                    "Cosmos memory operation failed with status " + cosmos.getStatusCode() + ".",
                    cosmos,
                    kind,
                    diagnostics);
        }
        return new CosmosStorageException(
                "Cosmos memory transport failed.", cause, CosmosStorageException.Kind.TRANSPORT, null);
    }

    static CosmosOperationDiagnostics diagnostics(CosmosException exception) {
        return new CosmosOperationDiagnostics(
                exception.getStatusCode(),
                blankToNull(exception.getActivityId()),
                exception.getRequestCharge() < 0 ? null : exception.getRequestCharge(),
                positiveOrNull(exception.getRetryAfterDuration()));
    }

    static CosmosOperationDiagnostics diagnostics(FeedResponse<?> response) {
        return new CosmosOperationDiagnostics(
                200, blankToNull(response.getActivityId()), response.getRequestCharge(), null);
    }

    static StorageConflictException conflict(String memoryId, long expected, Long actual) {
        return new StorageConflictException("Memory '" + memoryId
                + "' expected revision "
                + expected
                + " but current revision is "
                + (actual == null ? "absent" : actual)
                + ".");
    }

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    private static String identifier(String prefix, String... values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            String checked = requireNonBlank(value, "identifier component");
            if (checked.getBytes(StandardCharsets.UTF_8).length > 4096) {
                throw new ValidationException("Cosmos identifier components must not exceed 4096 UTF-8 bytes.");
            }
            update(digest, checked);
        }
        return "af1-" + prefix + "-" + ENCODER.encodeToString(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateState(MessageDigest digest, StateValue value) {
        switch (value) {
            case StateValue.NullValue _ -> digest.update((byte) 0);
            case StateValue.BooleanValue bool -> {
                digest.update((byte) 1);
                digest.update((byte) (bool.value() ? 1 : 0));
            }
            case StateValue.NumberValue number -> {
                digest.update((byte) 2);
                update(digest, number.value().toPlainString());
            }
            case StateValue.StringValue string -> {
                digest.update((byte) 3);
                update(digest, string.value());
            }
            case StateValue.ArrayValue array -> {
                digest.update((byte) 4);
                array.values().forEach(item -> updateState(digest, item));
            }
            case StateValue.ObjectValue object -> {
                digest.update((byte) 5);
                object.values().entrySet().stream()
                        .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                        .forEach(entry -> {
                            update(digest, entry.getKey());
                            updateState(digest, entry.getValue());
                        });
            }
        }
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
        return value == null || value.isZero() || value.isNegative() ? null : value;
    }
}
