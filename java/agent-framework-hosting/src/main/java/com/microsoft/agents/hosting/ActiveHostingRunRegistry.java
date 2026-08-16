// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ActiveHostingRunRegistry implements AutoCloseable {
    private final Object lock = new Object();

    private final int capacity;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private boolean closed;

    ActiveHostingRunRegistry(int capacity) {
        this.capacity = HostingValidation.positive(capacity, "capacity");
    }

    Entry register(
            HostingRequestContext context,
            HostingRouteKind kind,
            String routeId,
            String runId,
            RunCancellation cancellation) {
        Binding binding = new Binding(context.principalId(), context.isolationId(), kind, routeId, runId);
        Entry entry = new Entry(this, binding, cancellation);
        synchronized (lock) {
            if (closed) {
                throw new HostingException(HostingErrorCode.CONFLICT, "Hosting dispatcher is closed.");
            }
            if (entries.size() >= capacity) {
                throw new HostingException(
                        HostingErrorCode.TOO_MANY_REQUESTS, "Active hosting run capacity is exhausted.");
            }
            if (entries.putIfAbsent(runId, entry) != null) {
                throw new HostingException(HostingErrorCode.CONFLICT, "Generated run identifier collided.");
            }
        }
        entry.link(context.cancellation());
        return entry;
    }

    boolean cancel(HostingRequestContext context, HostingRouteKind kind, String routeId, String runId) {
        Entry entry;
        synchronized (lock) {
            entry = entries.get(HostingValidation.nonBlank(runId, "runId"));
        }
        if (entry == null) {
            throw new HostingException(HostingErrorCode.NOT_FOUND, "Active run was not found.");
        }
        Binding expected = new Binding(context.principalId(), context.isolationId(), kind, routeId, runId);
        if (!entry.binding.equals(expected)) {
            throw new HostingException(
                    HostingErrorCode.FORBIDDEN, "Active run does not belong to this principal, isolation, or route.");
        }
        return entry.cancel(HostingErrorCode.CLIENT_CANCELLED);
    }

    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    @Override
    public void close() {
        List<Entry> snapshot;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = new ArrayList<>(entries.values());
            entries.clear();
        }
        snapshot.forEach(entry -> entry.cancel(HostingErrorCode.CLIENT_CANCELLED));
    }

    private void remove(Entry entry) {
        synchronized (lock) {
            entries.remove(entry.binding.runId(), entry);
        }
    }

    static final class Entry {
        private final ActiveHostingRunRegistry owner;

        private final Binding binding;

        private final RunCancellation cancellation;

        private final AtomicBoolean finished = new AtomicBoolean();

        private final AtomicReference<HostingErrorCode> cancellationReason = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private final AtomicReference<RunCancellationRegistration> contextLink = new AtomicReference<>();

        private Entry(ActiveHostingRunRegistry owner, Binding binding, RunCancellation cancellation) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.binding = Objects.requireNonNull(binding, "binding");
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }

        String runId() {
            return binding.runId();
        }

        HostingErrorCode cancellationReason() {
            return cancellationReason.get();
        }

        RunCancellation cancellation() {
            return cancellation;
        }

        boolean cancel(HostingErrorCode reason) {
            Objects.requireNonNull(reason, "reason");
            cancellationReason.compareAndSet(null, reason);
            return cancellation.cancel();
        }

        void timeout(ScheduledFuture<?> task) {
            if (!timeout.compareAndSet(null, Objects.requireNonNull(task, "task"))) {
                task.cancel(false);
                throw new IllegalStateException("Run timeout was already registered.");
            }
            if (finished.get()) {
                task.cancel(false);
            }
        }

        void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            owner.remove(this);
            ScheduledFuture<?> timeoutTask = timeout.getAndSet(null);
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            RunCancellationRegistration registration = contextLink.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
        }

        private void link(RunCancellation requestCancellation) {
            RunCancellationRegistration registration =
                    RunCancellations.register(requestCancellation, () -> cancel(HostingErrorCode.CLIENT_CANCELLED));
            if (!contextLink.compareAndSet(null, registration)) {
                registration.close();
                throw new IllegalStateException("Request cancellation was already linked.");
            }
            if (finished.get()) {
                registration.close();
            }
        }
    }

    private record Binding(
            String principalId, String isolationId, HostingRouteKind kind, String routeId, String runId) {
        private Binding {
            principalId = HostingValidation.nonBlank(principalId, "principalId");
            isolationId = HostingValidation.nonBlank(isolationId, "isolationId");
            Objects.requireNonNull(kind, "kind");
            routeId = HostingValidation.routeId(routeId);
            runId = HostingValidation.nonBlank(runId, "runId");
        }
    }
}
