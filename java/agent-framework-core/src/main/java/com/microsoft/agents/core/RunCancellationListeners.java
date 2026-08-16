// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

final class RunCancellationListeners {
    private final AtomicLong nextId = new AtomicLong();

    private final ConcurrentHashMap<Long, Registration> registrations = new ConcurrentHashMap<>();

    RunCancellationRegistration register(
            BooleanSupplier terminal, BooleanSupplier cancellationRequested, Runnable listener) {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(listener, "listener");
        if (terminal.getAsBoolean()) {
            if (cancellationRequested.getAsBoolean()) {
                listener.run();
            }
            return () -> {};
        }
        long id = nextId.incrementAndGet();
        Registration registration = new Registration(id, listener);
        registrations.put(id, registration);
        if (terminal.getAsBoolean()) {
            if (cancellationRequested.getAsBoolean()) {
                registration.notifyCancellation();
            } else {
                registration.close();
            }
        }
        return registration;
    }

    void notifyCancellation() {
        try {
            registrations.values().forEach(registration -> {
                try {
                    registration.notifyCancellation();
                } catch (RuntimeException ignored) {
                    // One observer must not prevent cancellation or later observers.
                }
            });
        } finally {
            registrations.clear();
        }
    }

    void clear() {
        registrations.values().forEach(Registration::close);
        registrations.clear();
    }

    int size() {
        return registrations.size();
    }

    private final class Registration implements RunCancellationRegistration {
        private final long id;

        private final Runnable listener;

        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(long id, Runnable listener) {
            this.id = id;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                registrations.remove(id, this);
            }
        }

        private void notifyCancellation() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            registrations.remove(id, this);
            listener.run();
        }
    }
}
