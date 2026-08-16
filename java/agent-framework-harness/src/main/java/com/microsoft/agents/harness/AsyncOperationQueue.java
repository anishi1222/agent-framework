// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Serializes asynchronous provider operations without blocking a platform thread. */
final class AsyncOperationQueue {
    private final Object lock = new Object();

    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    <T> CompletionStage<T> submit(Supplier<? extends CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (lock) {
            CompletableFuture<T> current = tail.handle((ignored, failure) -> null)
                    .thenCompose(ignored -> Objects.requireNonNull(operation.get(), "operation returned null"))
                    .toCompletableFuture();
            tail = current.handle((ignored, failure) -> null);
            return current;
        }
    }
}
