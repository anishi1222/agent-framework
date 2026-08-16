// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import glide.api.models.exceptions.ClosingException;
import glide.api.models.exceptions.ConnectionException;
import glide.api.models.exceptions.GlideException;
import glide.api.models.exceptions.RequestException;
import glide.api.models.exceptions.TimeoutException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

final class GlideValkeyFailureMapper {
    private GlideValkeyFailureMapper() {}

    static <T> CompletionStage<T> mapStage(CompletionStage<T> stage) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
            } else {
                mapped.completeExceptionally(map(failure));
            }
        });
        return mapped.minimalCompletionStage();
    }

    static <T> CompletionStage<T> mapCreationStage(CompletionStage<T> stage) {
        CompletableFuture<T> mapped = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                mapped.complete(value);
            } else {
                mapped.completeExceptionally(mapCreation(failure));
            }
        });
        return mapped.minimalCompletionStage();
    }

    static RuntimeException mapCreation(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ValkeyStorageException mapped) {
            return mapped;
        }
        if (cause instanceof ClosingException) {
            return failure("The Valkey transport could not be established.", ValkeyStorageException.Kind.TRANSPORT);
        }
        return map(cause);
    }

    static RuntimeException map(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof ValkeyStorageException mapped) {
            return mapped;
        }
        if (cause instanceof TimeoutException) {
            return failure("The Valkey client timed out.", ValkeyStorageException.Kind.TIMEOUT);
        }
        if (cause instanceof ConnectionException) {
            return failure("The Valkey transport failed.", ValkeyStorageException.Kind.TRANSPORT);
        }
        if (cause instanceof ClosingException) {
            return failure("The Valkey client is closed.", ValkeyStorageException.Kind.CLOSED);
        }
        if (cause instanceof RequestException request) {
            ValkeyStorageException.Kind kind = isDataFailure(request)
                    ? ValkeyStorageException.Kind.INCOMPATIBLE_DATA
                    : isAuthenticationFailure(request)
                            ? ValkeyStorageException.Kind.AUTHENTICATION
                            : ValkeyStorageException.Kind.SERVICE;
            return failure("The Valkey server rejected the command.", kind);
        }
        if (cause instanceof GlideException) {
            return failure("The Valkey client could not complete the command.", ValkeyStorageException.Kind.SERVICE);
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return failure("The Valkey operation failed.", ValkeyStorageException.Kind.SERVICE);
    }

    private static boolean isAuthenticationFailure(RequestException failure) {
        String message =
                failure.getMessage() == null ? "" : failure.getMessage().toUpperCase(Locale.ROOT);
        return message.contains("NOAUTH")
                || message.contains("WRONGPASS")
                || message.contains("NOPERM")
                || message.contains("AUTHENTICATION");
    }

    private static boolean isDataFailure(RequestException failure) {
        String message =
                failure.getMessage() == null ? "" : failure.getMessage().toUpperCase(Locale.ROOT);
        return message.contains("AF_VALKEY_MESSAGE_BYTES")
                || message.contains("AF_VALKEY_DOCUMENT_BYTES")
                || message.contains("AF_VALKEY_INVALID_LIST_ENTRY")
                || message.contains("AF_VALKEY_WRONG_TYPE");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ValkeyStorageException failure(String message, ValkeyStorageException.Kind kind) {
        return new ValkeyStorageException(message, null, kind);
    }
}
