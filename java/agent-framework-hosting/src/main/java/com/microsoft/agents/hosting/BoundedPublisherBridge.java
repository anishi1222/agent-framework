// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Bridges one upstream publisher through bounded buffering and standard downstream demand.
 *
 * <p>The bridge supports one subscriber, never invokes a callback while holding its state lock,
 * rejects non-positive demand, cancels the source on overflow, and emits exactly one terminal
 * signal.
 *
 * @param <S> upstream item type
 * @param <T> downstream item type
 */
public final class BoundedPublisherBridge<S, T> implements Flow.Publisher<T>, Flow.Subscriber<S>, AutoCloseable {
    private final Object lock = new Object();

    private final ArrayDeque<T> pending = new ArrayDeque<>();

    private final int capacity;

    private final Function<? super S, ? extends T> mapper;

    private final Runnable cancellation;

    private final AtomicBoolean cancellationNotified = new AtomicBoolean();

    private final AtomicBoolean overflowPending;

    private Flow.Subscription upstream;

    private Flow.Subscriber<? super T> downstream;

    private boolean downstreamSubscribed;

    private long demand;

    private long upstreamOutstanding;

    private boolean upstreamCompleted;

    private boolean cancelled;

    private boolean terminalSignalled;

    private boolean draining;

    private Throwable failure;

    /**
     * Creates a bridge.
     *
     * @param capacity positive finite buffer capacity
     * @param mapper item mapper
     * @param cancellation callback that cancels the logical run
     */
    public BoundedPublisherBridge(int capacity, Function<? super S, ? extends T> mapper, Runnable cancellation) {
        this(capacity, mapper, cancellation, new AtomicBoolean());
    }

    BoundedPublisherBridge(
            int capacity,
            Function<? super S, ? extends T> mapper,
            Runnable cancellation,
            AtomicBoolean overflowPending) {
        this.capacity = HostingValidation.positive(capacity, "capacity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.overflowPending = Objects.requireNonNull(overflowPending, "overflowPending");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        boolean reject;
        synchronized (lock) {
            reject = downstreamSubscribed;
            if (!reject) {
                downstreamSubscribed = true;
                downstream = subscriber;
            }
        }
        if (reject) {
            reject(subscriber);
            return;
        }
        try {
            subscriber.onSubscribe(new DownstreamSubscription());
        } catch (RuntimeException ignored) {
            cancel();
            return;
        }
        requestUpstreamIfNeeded();
        drain();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        boolean reject;
        synchronized (lock) {
            reject = upstream != null || cancelled || terminalSignalled;
            if (!reject) {
                upstream = subscription;
            }
        }
        if (reject) {
            subscription.cancel();
            return;
        }
        requestUpstreamIfNeeded();
    }

    @Override
    public void onNext(S item) {
        T mapped;
        try {
            mapped = Objects.requireNonNull(mapper.apply(Objects.requireNonNull(item, "item")), "mapped item");
        } catch (RuntimeException failureValue) {
            failAndCancel(failureValue);
            return;
        }

        Flow.Subscription sourceToCancel = null;
        boolean overflow = false;
        synchronized (lock) {
            if (cancelled || upstreamCompleted || terminalSignalled) {
                return;
            }
            if (upstreamOutstanding > 0) {
                upstreamOutstanding--;
            }
            if (pending.size() >= capacity) {
                overflowPending.set(true);
                failure = new HostingStreamOverflowException(capacity);
                upstreamCompleted = true;
                pending.clear();
                sourceToCancel = upstream;
                overflow = true;
            } else {
                pending.addLast(mapped);
            }
        }
        drain();
        if (sourceToCancel != null) {
            sourceToCancel.cancel();
        }
        if (overflow) {
            notifyCancellation();
        }
        requestUpstreamIfNeeded();
    }

    @Override
    public void onError(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        synchronized (lock) {
            if (cancelled || upstreamCompleted || terminalSignalled) {
                return;
            }
            if (isOverflow(throwable)) {
                overflowPending.set(true);
            }
            failure = throwable;
            upstreamCompleted = true;
            pending.clear();
        }
        drain();
    }

    @Override
    public void onComplete() {
        synchronized (lock) {
            if (cancelled || upstreamCompleted || terminalSignalled) {
                return;
            }
            upstreamCompleted = true;
        }
        drain();
    }

    @Override
    public void close() {
        cancel();
    }

    /**
     * Reports the current buffered item count.
     *
     * @return buffered items
     */
    public int bufferedItemCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private void request(long count) {
        if (count <= 0) {
            failAndCancel(new IllegalArgumentException("Flow demand must be positive."));
            return;
        }
        synchronized (lock) {
            if (cancelled || terminalSignalled) {
                return;
            }
            demand = addCap(demand, count);
        }
        drain();
        requestUpstreamIfNeeded();
    }

    private void requestUpstreamIfNeeded() {
        Flow.Subscription source;
        long count;
        synchronized (lock) {
            if (upstream == null || cancelled || upstreamCompleted || terminalSignalled) {
                return;
            }
            long unsatisfied = demand - pending.size() - upstreamOutstanding;
            if (unsatisfied <= 0) {
                return;
            }
            count = unsatisfied;
            upstreamOutstanding = addCap(upstreamOutstanding, count);
            source = upstream;
        }
        try {
            source.request(count);
        } catch (RuntimeException failureValue) {
            failAndCancel(failureValue);
        }
    }

    private void drain() {
        synchronized (lock) {
            if (draining || downstream == null || cancelled || terminalSignalled) {
                return;
            }
            draining = true;
        }
        while (true) {
            Flow.Subscriber<? super T> target;
            T next = null;
            Throwable terminalFailure = null;
            boolean terminal = false;
            synchronized (lock) {
                if (cancelled || downstream == null) {
                    draining = false;
                    return;
                }
                target = downstream;
                if (demand > 0 && !pending.isEmpty()) {
                    demand--;
                    next = pending.removeFirst();
                } else if (upstreamCompleted && pending.isEmpty() && !terminalSignalled) {
                    terminalSignalled = true;
                    terminal = true;
                    terminalFailure = failure;
                    downstream = null;
                } else {
                    draining = false;
                    return;
                }
            }
            if (terminal) {
                signalTerminal(target, terminalFailure);
                synchronized (lock) {
                    draining = false;
                }
                return;
            }
            try {
                target.onNext(next);
            } catch (RuntimeException ignored) {
                cancel();
                synchronized (lock) {
                    draining = false;
                }
                return;
            }
            requestUpstreamIfNeeded();
        }
    }

    private void failAndCancel(Throwable throwable) {
        Flow.Subscription source;
        synchronized (lock) {
            if (cancelled || upstreamCompleted || terminalSignalled) {
                return;
            }
            if (isOverflow(throwable)) {
                overflowPending.set(true);
            }
            failure = Objects.requireNonNull(throwable, "throwable");
            upstreamCompleted = true;
            pending.clear();
            source = upstream;
        }
        drain();
        if (source != null) {
            source.cancel();
        }
        notifyCancellation();
    }

    private void cancel() {
        Flow.Subscription source;
        synchronized (lock) {
            if (cancelled || terminalSignalled) {
                return;
            }
            cancelled = true;
            pending.clear();
            source = upstream;
            downstream = null;
        }
        if (source != null) {
            source.cancel();
        }
        notifyCancellation();
    }

    private void notifyCancellation() {
        if (cancellationNotified.compareAndSet(false, true)) {
            cancellation.run();
        }
    }

    private static <T> void reject(Flow.Subscriber<? super T> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("This hosting bridge supports one subscriber."));
        } catch (RuntimeException ignored) {
            // Subscriber callbacks own their exceptions.
        }
    }

    private static void signalTerminal(Flow.Subscriber<?> subscriber, Throwable failureValue) {
        try {
            if (failureValue == null) {
                subscriber.onComplete();
            } else {
                subscriber.onError(failureValue);
            }
        } catch (RuntimeException ignored) {
            // Subscriber callbacks own their exceptions.
        }
    }

    private static long addCap(long left, long right) {
        long sum = left + right;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static boolean isOverflow(Throwable failure) {
        return failure instanceof HostingException hosting && hosting.error().code() == HostingErrorCode.OVERFLOW;
    }

    private final class DownstreamSubscription implements Flow.Subscription {
        @Override
        public void request(long count) {
            BoundedPublisherBridge.this.request(count);
        }

        @Override
        public void cancel() {
            BoundedPublisherBridge.this.cancel();
        }
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
