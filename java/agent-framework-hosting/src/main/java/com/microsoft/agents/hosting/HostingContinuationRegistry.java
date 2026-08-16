// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class HostingContinuationRegistry implements AutoCloseable {
    private static final int TOKEN_BYTES = 32;

    private final Object lock = new Object();

    private final int capacity;

    private final java.time.Duration ttl;

    private final Clock clock;

    private final SecureRandom random;

    private final LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();

    private int available;

    HostingContinuationRegistry(HostingLimits limits) {
        this(limits, Clock.systemUTC(), new SecureRandom());
    }

    HostingContinuationRegistry(HostingLimits limits, Clock clock, SecureRandom random) {
        Objects.requireNonNull(limits, "limits");
        capacity = limits.maxProcessLocalContinuations();
        ttl = limits.continuationTtl();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    HostingContinuationDescriptor issue(
            Binding binding, Object payload, List<HostingApprovalRequest> approvals, Runnable discard) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(payload, "payload");
        List<HostingApprovalRequest> safeApprovals = List.copyOf(approvals);
        Objects.requireNonNull(discard, "discard");
        List<Runnable> expired = cleanup();
        runCallbacks(expired);
        Instant expiresAt = clock.instant().plus(ttl);
        String token;
        synchronized (lock) {
            if (available >= capacity) {
                throw new HostingException(
                        HostingErrorCode.TOO_MANY_REQUESTS, "Process-local continuation capacity is exhausted.");
            }
            do {
                byte[] bytes = new byte[TOKEN_BYTES];
                random.nextBytes(bytes);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            } while (slots.containsKey(token));
            slots.put(token, new Slot(binding, payload, expiresAt, discard, SlotState.AVAILABLE));
            available++;
            trimTombstones();
        }
        return new HostingContinuationDescriptor(token, binding.type(), expiresAt, safeApprovals);
    }

    Object consume(
            HostingRequestContext context,
            HostingRouteKind kind,
            String routeId,
            String runId,
            HostingContinuationType type,
            String token) {
        Objects.requireNonNull(context, "context");
        Binding expected = new Binding(
                context.principalId(),
                context.isolationId(),
                kind,
                HostingValidation.routeId(routeId),
                HostingValidation.nonBlank(runId, "runId"),
                type);
        Runnable expiryCallback = null;
        Object payload = null;
        HostingException deferredFailure = null;
        synchronized (lock) {
            Slot slot = slots.get(HostingValidation.nonBlank(token, "token"));
            if (slot == null) {
                throw new HostingException(HostingErrorCode.NOT_FOUND, "Continuation was not found.");
            }
            if (slot.state() == SlotState.CONSUMED) {
                throw new HostingException(
                        HostingErrorCode.CONTINUATION_REPLAYED, "Continuation was already consumed.");
            }
            if (slot.state() == SlotState.EXPIRED || !clock.instant().isBefore(slot.expiresAt())) {
                if (slot.state() == SlotState.AVAILABLE) {
                    available--;
                    expiryCallback = slot.discard();
                    slots.put(token, slot.withState(SlotState.EXPIRED));
                }
                deferredFailure = new HostingException(HostingErrorCode.CONTINUATION_EXPIRED, "Continuation expired.");
            } else if (!slot.binding().principalId().equals(expected.principalId())
                    || !slot.binding().isolationId().equals(expected.isolationId())) {
                throw new HostingException(
                        HostingErrorCode.FORBIDDEN, "Continuation does not belong to this principal or isolation.");
            } else if (slot.binding().kind() != expected.kind()
                    || !slot.binding().routeId().equals(expected.routeId())
                    || !slot.binding().runId().equals(expected.runId())
                    || slot.binding().type() != expected.type()) {
                throw new HostingException(
                        HostingErrorCode.CONFLICT, "Continuation binding does not match this route, run, or type.");
            } else {
                slots.put(token, slot.withState(SlotState.CONSUMED));
                available--;
                payload = slot.payload();
            }
        }
        if (expiryCallback != null) {
            runCallbacks(List.of(expiryCallback));
        }
        if (deferredFailure != null) {
            throw deferredFailure;
        }
        return payload;
    }

    int availableCount() {
        synchronized (lock) {
            return available;
        }
    }

    void discard(String token) {
        Runnable callback = null;
        synchronized (lock) {
            Slot slot = slots.get(HostingValidation.nonBlank(token, "token"));
            if (slot != null && slot.state() == SlotState.AVAILABLE) {
                slots.put(token, slot.withState(SlotState.CONSUMED));
                available--;
                callback = slot.discard();
            }
        }
        if (callback != null) {
            runCallbacks(List.of(callback));
        }
    }

    @Override
    public void close() {
        ArrayList<Runnable> callbacks = new ArrayList<>();
        synchronized (lock) {
            slots.values().stream()
                    .filter(slot -> slot.state() == SlotState.AVAILABLE)
                    .map(Slot::discard)
                    .forEach(callbacks::add);
            slots.clear();
            available = 0;
        }
        runCallbacks(callbacks);
    }

    private List<Runnable> cleanup() {
        ArrayList<Runnable> callbacks = new ArrayList<>();
        Instant now = clock.instant();
        synchronized (lock) {
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                Slot slot = entry.getValue();
                if (slot.state() == SlotState.AVAILABLE && !now.isBefore(slot.expiresAt())) {
                    available--;
                    callbacks.add(slot.discard());
                    entry.setValue(slot.withState(SlotState.EXPIRED));
                }
            }
            trimTombstones();
        }
        return List.copyOf(callbacks);
    }

    private void trimTombstones() {
        int maximumSlots = Math.max(capacity * 2, capacity + 16);
        if (slots.size() <= maximumSlots) {
            return;
        }
        Iterator<Map.Entry<String, Slot>> iterator = slots.entrySet().iterator();
        while (slots.size() > maximumSlots && iterator.hasNext()) {
            Map.Entry<String, Slot> entry = iterator.next();
            if (entry.getValue().state() != SlotState.AVAILABLE) {
                iterator.remove();
            }
        }
    }

    private static void runCallbacks(List<Runnable> callbacks) {
        callbacks.forEach(callback -> {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup must not suppress the stable hosting boundary.
            }
        });
    }

    record Binding(
            String principalId,
            String isolationId,
            HostingRouteKind kind,
            String routeId,
            String runId,
            HostingContinuationType type) {
        Binding {
            principalId = HostingValidation.nonBlank(principalId, "principalId");
            isolationId = HostingValidation.nonBlank(isolationId, "isolationId");
            Objects.requireNonNull(kind, "kind");
            routeId = HostingValidation.routeId(routeId);
            runId = HostingValidation.nonBlank(runId, "runId");
            Objects.requireNonNull(type, "type");
        }
    }

    private record Slot(Binding binding, Object payload, Instant expiresAt, Runnable discard, SlotState state) {
        private Slot {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(discard, "discard");
            Objects.requireNonNull(state, "state");
        }

        private Slot withState(SlotState replacement) {
            return new Slot(binding, payload, expiresAt, discard, replacement);
        }
    }

    private enum SlotState {
        AVAILABLE,
        CONSUMED,
        EXPIRED
    }
}
