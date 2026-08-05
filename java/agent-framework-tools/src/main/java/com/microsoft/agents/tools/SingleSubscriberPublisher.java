// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;

final class SingleSubscriberPublisher<T> implements Flow.Publisher<T> {
    private final ArrayDeque<T> pending = new ArrayDeque<>();

    private final Runnable starter;

    private final Runnable cancellation;

    private Flow.Subscriber<? super T> subscriber;

    private long demand;

    private boolean subscribed;

    private boolean cancelled;

    private boolean completed;

    private boolean terminalSignalled;

    private boolean draining;

    private Throwable failure;

    SingleSubscriberPublisher(Runnable starter, Runnable cancellation) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> nextSubscriber) {
        Objects.requireNonNull(nextSubscriber, "subscriber");
        boolean rejected;
        synchronized (this) {
            rejected = subscribed;
            if (!rejected) {
                subscribed = true;
                subscriber = nextSubscriber;
            }
        }
        if (rejected) {
            nextSubscriber.onSubscribe(new EmptySubscription());
            nextSubscriber.onError(new IllegalStateException("This run publisher supports one subscriber."));
            return;
        }
        nextSubscriber.onSubscribe(new RunSubscription());
        starter.run();
        drain();
    }

    void emit(T value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (completed || cancelled) {
                return;
            }
            pending.addLast(value);
        }
        drain();
    }

    void complete() {
        synchronized (this) {
            if (completed || cancelled) {
                return;
            }
            completed = true;
        }
        drain();
    }

    void fail(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        synchronized (this) {
            if (completed || cancelled) {
                return;
            }
            failure = throwable;
            pending.clear();
            completed = true;
        }
        drain();
    }

    private void request(long count) {
        if (count <= 0) {
            fail(new IllegalArgumentException("Flow demand must be positive."));
            cancellation.run();
            return;
        }
        synchronized (this) {
            demand = addCap(demand, count);
        }
        drain();
    }

    private void cancelSubscription() {
        boolean notify;
        synchronized (this) {
            notify = !cancelled && !terminalSignalled;
            cancelled = true;
            pending.clear();
        }
        if (notify) {
            cancellation.run();
        }
    }

    private void drain() {
        synchronized (this) {
            if (draining || subscriber == null || cancelled) {
                return;
            }
            draining = true;
        }
        while (true) {
            Flow.Subscriber<? super T> target;
            T value;
            Throwable terminalFailure;
            boolean terminal;
            synchronized (this) {
                if (cancelled) {
                    draining = false;
                    return;
                }
                target = subscriber;
                if (demand > 0 && !pending.isEmpty()) {
                    demand--;
                    value = pending.removeFirst();
                    terminal = false;
                    terminalFailure = null;
                } else if (completed && pending.isEmpty() && !terminalSignalled) {
                    terminalSignalled = true;
                    value = null;
                    terminal = true;
                    terminalFailure = failure;
                } else {
                    draining = false;
                    return;
                }
            }
            if (terminal) {
                if (terminalFailure == null) {
                    target.onComplete();
                } else {
                    target.onError(terminalFailure);
                }
                synchronized (this) {
                    draining = false;
                }
                return;
            }
            try {
                target.onNext(value);
            } catch (RuntimeException subscriberFailure) {
                cancelSubscription();
                synchronized (this) {
                    draining = false;
                }
                return;
            }
        }
    }

    private static long addCap(long left, long right) {
        long sum = left + right;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private final class RunSubscription implements Flow.Subscription {
        @Override
        public void request(long count) {
            SingleSubscriberPublisher.this.request(count);
        }

        @Override
        public void cancel() {
            cancelSubscription();
        }
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
