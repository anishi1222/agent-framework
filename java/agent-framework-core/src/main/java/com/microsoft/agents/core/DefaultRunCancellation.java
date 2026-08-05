// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides the default thread-safe, idempotent cancellation signal.
 *
 * <p>The creator owns this signal. Consumers may retain the returned notification stage but cannot
 * use that stage to complete or reset the cancellation state.
 */
public final class DefaultRunCancellation implements RunCancellation {
    private final AtomicBoolean requested = new AtomicBoolean();

    private final CompletableFuture<Void> notification = new CompletableFuture<>();

    private final CompletionStage<Void> notificationView = notification.minimalCompletionStage();

    @Override
    public boolean cancel() {
        if (!requested.compareAndSet(false, true)) {
            return false;
        }
        notification.complete(null);
        return true;
    }

    @Override
    public boolean isCancellationRequested() {
        return requested.get();
    }

    @Override
    public CompletionStage<Void> cancelledAsync() {
        return notificationView;
    }
}
