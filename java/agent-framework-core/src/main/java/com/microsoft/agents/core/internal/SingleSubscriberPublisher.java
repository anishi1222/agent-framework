// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import com.microsoft.agents.core.AgentExecutionException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.IntFunction;

/**
 * Implements the framework's bounded, single-subscriber run-publisher contract.
 *
 * <p>This public type is an internal cross-module runtime component, not an application extension
 * point. It starts work only after subscription, buffers at most the configured number of updates,
 * serializes signals, and propagates subscription cancellation through the supplied callback.
 *
 * @param <T> update type
 */
public final class SingleSubscriberPublisher<T> implements Flow.Publisher<T> {
    /**
     * Selects whether emitted updates are retained for a subscriber or discarded at the source.
     */
    public enum UpdateMode {
        /**
         * Retains emitted updates in the bounded buffer until demanded by the subscriber.
         */
        BUFFERED,

        /**
         * Discards emitted updates without retaining them.
         */
        DISCARD
    }

    private final ArrayDeque<T> pending = new ArrayDeque<>();

    private final Runnable starter;

    private final Runnable cancellation;

    private final int maxBufferedUpdates;

    private final IntFunction<? extends RuntimeException> overflowFactory;

    private final Runnable overflowAction;

    private final UpdateMode updateMode;

    private Flow.Subscriber<? super T> subscriber;

    private long demand;

    private boolean subscribed;

    private boolean cancelled;

    private boolean completed;

    private boolean terminalSignalled;

    private boolean draining;

    private boolean terminalBlocked;

    private Throwable failure;

    /**
     * Creates a publisher using the standard execution exception for buffer overflow.
     *
     * @param starter callback that starts the cold run after subscription
     * @param cancellation callback that cancels the underlying run
     * @param maxBufferedUpdates positive finite buffer size
     */
    public SingleSubscriberPublisher(Runnable starter, Runnable cancellation, int maxBufferedUpdates) {
        this(
                starter,
                cancellation,
                maxBufferedUpdates,
                UpdateMode.BUFFERED,
                limit -> new AgentExecutionException(
                        "Streaming update buffer exceeded maxBufferedUpdates=" + limit + "."),
                () -> {});
    }

    /**
     * Creates a publisher with a module-specific overflow failure.
     *
     * @param starter callback that starts the cold run after subscription
     * @param cancellation callback that cancels the underlying run
     * @param maxBufferedUpdates positive finite buffer size
     * @param overflowFactory exception factory receiving the configured buffer size
     */
    public SingleSubscriberPublisher(
            Runnable starter,
            Runnable cancellation,
            int maxBufferedUpdates,
            IntFunction<? extends RuntimeException> overflowFactory) {
        this(starter, cancellation, maxBufferedUpdates, UpdateMode.BUFFERED, overflowFactory, () -> {});
    }

    /**
     * Creates a publisher with explicit update retention and a module-specific overflow failure.
     *
     * @param starter callback that starts the cold run after subscription
     * @param cancellation callback that cancels the underlying run
     * @param maxBufferedUpdates positive finite buffer size
     * @param updateMode update retention mode
     * @param overflowFactory exception factory receiving the configured buffer size
     */
    public SingleSubscriberPublisher(
            Runnable starter,
            Runnable cancellation,
            int maxBufferedUpdates,
            UpdateMode updateMode,
            IntFunction<? extends RuntimeException> overflowFactory) {
        this(starter, cancellation, maxBufferedUpdates, updateMode, overflowFactory, () -> {});
    }

    /**
     * Creates a publisher with explicit retention and a pre-terminal overflow action.
     *
     * @param starter callback that starts the cold run after subscription
     * @param cancellation callback that cancels the underlying run after subscriber cancellation
     * @param maxBufferedUpdates positive finite buffer size
     * @param updateMode update retention mode
     * @param overflowFactory exception factory receiving the configured buffer size
     * @param overflowAction action run after overflow is selected but before it is signalled
     */
    public SingleSubscriberPublisher(
            Runnable starter,
            Runnable cancellation,
            int maxBufferedUpdates,
            UpdateMode updateMode,
            IntFunction<? extends RuntimeException> overflowFactory,
            Runnable overflowAction) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (maxBufferedUpdates <= 0) {
            throw new IllegalArgumentException("maxBufferedUpdates must be greater than zero.");
        }
        this.maxBufferedUpdates = maxBufferedUpdates;
        this.updateMode = Objects.requireNonNull(updateMode, "updateMode");
        this.overflowFactory = Objects.requireNonNull(overflowFactory, "overflowFactory");
        this.overflowAction = Objects.requireNonNull(overflowAction, "overflowAction");
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
            signalRejectedSubscriber(nextSubscriber);
            return;
        }
        try {
            nextSubscriber.onSubscribe(new RunSubscription());
        } catch (RuntimeException subscriberFailure) {
            cancelSubscription();
            return;
        }
        synchronized (this) {
            if (completed || cancelled) {
                return;
            }
        }
        try {
            starter.run();
        } catch (RuntimeException failure) {
            fail(failure);
        }
        drain();
    }

    /**
     * Emits an update or fails the run when the bounded buffer is full.
     *
     * @param value non-null update
     */
    public void emit(T value) {
        Objects.requireNonNull(value, "value");
        RuntimeException overflow = null;
        synchronized (this) {
            if (completed || cancelled) {
                return;
            }
            if (updateMode == UpdateMode.DISCARD) {
                return;
            }
            if (pending.size() >= maxBufferedUpdates) {
                overflow = Objects.requireNonNull(overflowFactory.apply(maxBufferedUpdates), "overflowFactory result");
                failure = overflow;
                pending.clear();
                completed = true;
            } else {
                pending.addLast(value);
            }
        }
        if (overflow != null) {
            overflowAction.run();
            drain();
            throw overflow;
        }
        drain();
    }

    /**
     * Completes the publisher after buffered updates have been delivered.
     */
    public void complete() {
        complete(() -> {});
    }

    /**
     * Completes the publisher and runs an action before the terminal signal is drained.
     *
     * @param terminalAction action invoked exactly once by this call
     */
    public void complete(Runnable terminalAction) {
        Objects.requireNonNull(terminalAction, "terminalAction");
        synchronized (this) {
            if (completed || cancelled) {
                terminalAction.run();
                return;
            }
            completed = true;
        }
        terminalAction.run();
        drain();
    }

    /**
     * Fails the publisher and discards buffered updates.
     *
     * @param throwable terminal failure
     * @return {@code true} only when this call established the terminal failure
     */
    public boolean fail(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        synchronized (this) {
            if (completed || cancelled) {
                return false;
            }
            failure = throwable;
            pending.clear();
            completed = true;
        }
        drain();
        return true;
    }

    /**
     * Delays any terminal subscriber signal until the supplied cleanup stage settles.
     *
     * <p>The stage outcome does not replace the publisher's selected terminal outcome. This method
     * must be called before subscription.
     *
     * @param cleanup logical cleanup that must finish before terminal delivery
     */
    public void delayTerminalUntil(CompletionStage<?> cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        synchronized (this) {
            if (subscribed) {
                throw new IllegalStateException("Terminal cleanup must be configured before subscription.");
            }
            if (terminalBlocked) {
                throw new IllegalStateException("Terminal cleanup is already configured.");
            }
            terminalBlocked = true;
        }
        cleanup.whenComplete((ignored, failure) -> {
            synchronized (this) {
                terminalBlocked = false;
            }
            drain();
        });
    }

    private static <T> void signalRejectedSubscriber(Flow.Subscriber<? super T> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("This run publisher supports one subscriber."));
        } catch (RuntimeException ignored) {
            // A subscriber owns exceptions thrown from its signal methods.
        }
    }

    private void request(long count) {
        if (count <= 0) {
            if (fail(new IllegalArgumentException("Flow demand must be positive."))) {
                cancellation.run();
            }
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
            notify = !cancelled && !completed && !terminalSignalled;
            cancelled = true;
            pending.clear();
            subscriber = null;
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
                } else if (completed && pending.isEmpty() && !terminalSignalled && !terminalBlocked) {
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
                try {
                    if (terminalFailure == null) {
                        target.onComplete();
                    } else {
                        target.onError(terminalFailure);
                    }
                } catch (RuntimeException ignored) {
                    // A subscriber owns exceptions thrown from its signal methods.
                }
                synchronized (this) {
                    subscriber = null;
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

    synchronized boolean hasSubscriber() {
        return subscriber != null;
    }

    synchronized int bufferedUpdateCount() {
        return pending.size();
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

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
