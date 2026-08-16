// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.DecodeException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ServiceResponseException;
import com.azure.core.http.HttpHeaderName;
import com.microsoft.agents.azure.AzureAuthenticationException;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

final class AzureAISearchSdkSupport {
    private AzureAISearchSdkSupport() {}

    static <T> CompletionStage<T> stage(
            Mono<T> mono, AzureAISearchOptions options, RunCancellation cancellation, String operation) {
        Objects.requireNonNull(mono, "mono");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(operation, "operation");
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
                result.completeExceptionally(mapFailure(failure, operation));
            }
        });
        return result.minimalCompletionStage();
    }

    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static RuntimeException mapFailure(Throwable failure, String operation) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RunCancelledException cancelled) {
            return cancelled;
        }
        if (cause instanceof AzureAISearchValidationException validation) {
            return validation;
        }
        if (cause instanceof AzureAISearchException mapped) {
            return mapped;
        }
        if (cause instanceof CancellationException) {
            return new RunCancelledException();
        }
        if (cause instanceof TimeoutException) {
            return new AzureAISearchException(AzureAISearchException.Kind.TIMEOUT, operation, null, null, null, cause);
        }
        if (cause instanceof ClientAuthenticationException || cause instanceof AzureAuthenticationException) {
            return new AzureAISearchException(
                    AzureAISearchException.Kind.AUTHENTICATION,
                    operation,
                    status(cause),
                    requestId(cause),
                    null,
                    cause);
        }
        if (cause instanceof HttpResponseException response) {
            Integer status = status(response);
            AzureAISearchException.Kind kind = status != null && (status == 401 || status == 403)
                    ? AzureAISearchException.Kind.AUTHENTICATION
                    : status != null && status == 404
                            ? AzureAISearchException.Kind.NOT_FOUND
                            : AzureAISearchException.Kind.SERVICE;
            return new AzureAISearchException(
                    kind, operation, status, requestId(response), retryAfter(response), response);
        }
        if (cause instanceof DecodeException
                || causedBy(cause, DecodeException.class)
                || causedByPackage(cause, "com.azure.json.")) {
            return new AzureAISearchException(
                    AzureAISearchException.Kind.DATA_CONTRACT, operation, null, null, null, cause);
        }
        if (cause instanceof ServiceResponseException
                || cause instanceof IllegalArgumentException
                || cause instanceof IllegalStateException
                || cause instanceof ClassCastException) {
            return new AzureAISearchException(
                    AzureAISearchException.Kind.DATA_CONTRACT, operation, null, null, null, cause);
        }
        if (cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof IOException) {
            return new AzureAISearchException(
                    AzureAISearchException.Kind.TRANSPORT, operation, null, null, null, cause);
        }
        return new AzureAISearchException(AzureAISearchException.Kind.TRANSPORT, operation, null, null, null, cause);
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure.getCause();
        for (int depth = 0; current != null && current != failure && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean causedByPackage(Throwable failure, String packagePrefix) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.getClass().getName().startsWith(packagePrefix)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                return false;
            }
            current = next;
        }
        return false;
    }

    static AzureAISearchException invalidResponse(String operation, Throwable cause) {
        return new AzureAISearchException(
                AzureAISearchException.Kind.DATA_CONTRACT, operation, null, null, null, cause);
    }

    private static Integer status(Throwable failure) {
        return failure instanceof HttpResponseException response && response.getResponse() != null
                ? response.getResponse().getStatusCode()
                : null;
    }

    private static String requestId(Throwable failure) {
        if (!(failure instanceof HttpResponseException response) || response.getResponse() == null) {
            return null;
        }
        String value = response.getResponse().getHeaders().getValue(HttpHeaderName.fromString("x-ms-request-id"));
        if (value == null || value.isBlank()) {
            value = response.getResponse().getHeaders().getValue(HttpHeaderName.fromString("request-id"));
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        String bounded = value.trim();
        return bounded.length() <= 256 ? bounded : bounded.substring(0, 256);
    }

    private static Duration retryAfter(HttpResponseException failure) {
        if (failure.getResponse() == null) {
            return null;
        }
        String milliseconds =
                failure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("x-ms-retry-after-ms"));
        Duration parsed = positiveDuration(milliseconds, true);
        if (parsed != null) {
            return parsed;
        }
        String seconds = failure.getResponse().getHeaders().getValue(HttpHeaderName.fromString("retry-after"));
        return positiveDuration(seconds, false);
    }

    private static Duration positiveDuration(String value, boolean milliseconds) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                return null;
            }
            Duration duration = milliseconds ? Duration.ofMillis(parsed) : Duration.ofSeconds(parsed);
            return duration.compareTo(Duration.ofHours(1)) <= 0 ? duration : Duration.ofHours(1);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
