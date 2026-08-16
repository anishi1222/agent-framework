// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.ValidationException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

final class MCPEventPublisher implements Flow.Publisher<MCPClientEvent>, AutoCloseable {
    private final int capacity;

    private final Object stateLock = new Object();

    private final ArrayDeque<MCPClientEvent> queue = new ArrayDeque<>();

    private final AtomicInteger drainWork = new AtomicInteger();

    private SubscriberSubscription subscription;

    private boolean subscribed;

    private boolean closed;

    private boolean terminalSignalled;

    private Throwable failure;

    MCPEventPublisher(int capacity) {
        this.capacity = MCPValidation.positive(capacity, "capacity");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super MCPClientEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        SubscriberSubscription nextSubscription;
        synchronized (stateLock) {
            if (subscribed) {
                nextSubscription = null;
            } else {
                subscribed = true;
                nextSubscription = new SubscriberSubscription(subscriber);
                subscription = nextSubscription;
            }
        }
        if (nextSubscription == null) {
            signalRejectedSubscriber(subscriber);
            return;
        }
        try {
            subscriber.onSubscribe(nextSubscription);
        } catch (RuntimeException subscriberFailure) {
            nextSubscription.cancel();
            return;
        }
        synchronized (stateLock) {
            if (!nextSubscription.cancelled) {
                nextSubscription.ready = true;
            }
        }
        drain();
    }

    void emit(MCPClientEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (stateLock) {
            if (closed || failure != null || isCancelled()) {
                return;
            }
            if (queue.size() >= capacity) {
                failure = new MCPException("MCP event buffer overflowed at " + capacity
                        + " items; consume events or increase maxEventBuffer.");
                queue.clear();
            } else {
                queue.addLast(event);
            }
        }
        drain();
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed || failure != null || isCancelled()) {
                return;
            }
            closed = true;
        }
        drain();
    }

    private void drain() {
        if (drainWork.getAndIncrement() != 0) {
            return;
        }
        int missed = 1;
        do {
            drainAvailable();
            missed = drainWork.addAndGet(-missed);
        } while (missed != 0);
    }

    private void drainAvailable() {
        while (true) {
            SubscriberSubscription active;
            Flow.Subscriber<? super MCPClientEvent> target;
            MCPClientEvent event;
            Throwable terminalFailure;
            boolean terminal;
            synchronized (stateLock) {
                active = subscription;
                if (active == null || !active.ready || active.cancelled) {
                    return;
                }
                target = active.subscriber;
                if (failure != null && !terminalSignalled) {
                    terminalSignalled = true;
                    active.cancelled = true;
                    queue.clear();
                    event = null;
                    terminalFailure = failure;
                    terminal = true;
                } else if (active.demand > 0 && !queue.isEmpty()) {
                    active.demand--;
                    event = queue.removeFirst();
                    terminalFailure = null;
                    terminal = false;
                } else if (closed && queue.isEmpty() && !terminalSignalled) {
                    terminalSignalled = true;
                    active.cancelled = true;
                    event = null;
                    terminalFailure = null;
                    terminal = true;
                } else {
                    return;
                }
            }
            if (terminal) {
                signalTerminal(target, terminalFailure);
                return;
            }
            try {
                target.onNext(event);
            } catch (RuntimeException subscriberFailure) {
                cancel(active);
                return;
            }
        }
    }

    private static void signalRejectedSubscriber(Flow.Subscriber<? super MCPClientEvent> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("MCP event publisher permits one subscriber."));
        } catch (RuntimeException ignored) {
            // A subscriber owns exceptions thrown from its signal methods.
        }
    }

    private static void signalTerminal(Flow.Subscriber<? super MCPClientEvent> subscriber, Throwable terminalFailure) {
        try {
            if (terminalFailure == null) {
                subscriber.onComplete();
            } else {
                subscriber.onError(terminalFailure);
            }
        } catch (RuntimeException ignored) {
            // A subscriber owns exceptions thrown from its signal methods.
        }
    }

    private boolean isCancelled() {
        return subscription != null && subscription.cancelled;
    }

    private void request(SubscriberSubscription active, long count) {
        synchronized (stateLock) {
            if (subscription != active || active.cancelled || terminalSignalled) {
                return;
            }
            if (count <= 0) {
                if (failure == null) {
                    failure = new ValidationException("Flow demand must be positive.");
                    queue.clear();
                }
            } else {
                active.demand = addCap(active.demand, count);
            }
        }
        drain();
    }

    private void cancel(SubscriberSubscription active) {
        synchronized (stateLock) {
            if (subscription != active || active.cancelled) {
                return;
            }
            active.cancelled = true;
            queue.clear();
        }
    }

    private final class SubscriberSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super MCPClientEvent> subscriber;

        private long demand;

        private boolean ready;

        private boolean cancelled;

        private SubscriberSubscription(Flow.Subscriber<? super MCPClientEvent> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long count) {
            MCPEventPublisher.this.request(this, count);
        }

        @Override
        public void cancel() {
            MCPEventPublisher.this.cancel(this);
        }
    }

    private static long addCap(long left, long right) {
        long result = left + right;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
