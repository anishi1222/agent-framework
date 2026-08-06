// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ObservableRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class RunCancellationScope implements ObservableRunCancellation, AutoCloseable {
    private final RunCancellation delegate;

    private final AtomicLong nextId = new AtomicLong();

    private final ConcurrentHashMap<Long, Registration> registrations = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final RunCancellationRegistration upstreamRegistration;

    RunCancellationScope(RunCancellation delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.upstreamRegistration = RunCancellations.register(delegate, this::notifyCancellation);
    }

    @Override
    public boolean cancel() {
        boolean initiated = delegate.cancel();
        if (initiated || delegate.isCancellationRequested()) {
            notifyCancellation();
        }
        return initiated;
    }

    @Override
    public boolean isCancellationRequested() {
        return delegate.isCancellationRequested();
    }

    @Override
    public CompletionStage<Void> cancelledAsync() {
        return delegate.cancelledAsync();
    }

    @Override
    public RunCancellationRegistration register(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (isCancellationRequested()) {
            listener.run();
            return () -> {};
        }
        if (closed.get()) {
            return () -> {};
        }
        long id = nextId.incrementAndGet();
        Registration registration = new Registration(id, listener);
        registrations.put(id, registration);
        if (isCancellationRequested()) {
            registration.notifyCancellation();
        } else if (closed.get()) {
            registration.close();
        }
        return registration;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        upstreamRegistration.close();
        registrations.values().forEach(Registration::close);
        registrations.clear();
    }

    int activeRegistrationCount() {
        return registrations.size();
    }

    private void notifyCancellation() {
        registrations.values().forEach(Registration::notifyCancellation);
        registrations.clear();
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
