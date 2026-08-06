// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class FallbackRunCancellationRegistration implements RunCancellationRegistration {
    private final AtomicReference<Runnable> listener;

    FallbackRunCancellationRegistration(Runnable listener) {
        this.listener = new AtomicReference<>(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void close() {
        listener.set(null);
    }

    void notifyCancellation() {
        Runnable callback = listener.getAndSet(null);
        if (callback != null) {
            callback.run();
        }
    }

    boolean hasListener() {
        return listener.get() != null;
    }
}
