// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class HostedStreamingPublisher<S> implements Flow.Publisher<HostingEvent> {
    private final Flow.Publisher<S> source;

    private final int capacity;

    private final Function<? super S, HostingEvent> mapper;

    private final Runnable cancellation;

    private final Function<Throwable, HostingOutcome> failureMapper;

    private final Supplier<HostingOutcome> success;

    private final Consumer<HostingOutcome> discardedOutcome;

    private final AtomicBoolean overflowPending;

    private final CompletableFuture<HostingOutcome> terminal;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    private final AtomicReference<BoundedPublisherBridge<S, HostingEvent>> bridge = new AtomicReference<>();

    private final AtomicReference<Flow.Subscription> sourceSubscription = new AtomicReference<>();

    HostedStreamingPublisher(
            Flow.Publisher<S> source,
            int capacity,
            Function<? super S, HostingEvent> mapper,
            Runnable cancellation,
            Function<Throwable, HostingOutcome> failureMapper,
            Supplier<HostingOutcome> success,
            Consumer<HostingOutcome> discardedOutcome,
            AtomicBoolean overflowPending,
            CompletableFuture<HostingOutcome> terminal) {
        this.source = Objects.requireNonNull(source, "source");
        this.capacity = HostingValidation.positive(capacity, "capacity");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.failureMapper = Objects.requireNonNull(failureMapper, "failureMapper");
        this.success = Objects.requireNonNull(success, "success");
        this.discardedOutcome = Objects.requireNonNull(discardedOutcome, "discardedOutcome");
        this.overflowPending = Objects.requireNonNull(overflowPending, "overflowPending");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        terminal.whenComplete((outcome, failure) -> {
            if (outcome != null
                    && (outcome.status() == HostingOutcomeStatus.CANCELLED
                            || outcome.status() == HostingOutcomeStatus.OVERFLOW)) {
                Flow.Subscription subscription = sourceSubscription.get();
                if (subscription != null) {
                    subscription.cancel();
                }
            }
            BoundedPublisherBridge<S, HostingEvent> current = bridge.get();
            if (current != null) {
                current.onComplete();
            }
        });
    }

    @Override
    public void subscribe(Flow.Subscriber<? super HostingEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            reject(subscriber);
            return;
        }
        if (terminal.isDone()) {
            completeEmpty(subscriber);
            return;
        }
        BoundedPublisherBridge<S, HostingEvent> current =
                new BoundedPublisherBridge<>(capacity, mapper, cancellation, overflowPending);
        bridge.set(current);
        current.subscribe(new TerminalSubscriber(subscriber));
        if (terminal.isDone()) {
            current.onComplete();
            return;
        }
        source.subscribe(new InterceptingSubscriber(current));
    }

    private final class InterceptingSubscriber implements Flow.Subscriber<S> {
        private final BoundedPublisherBridge<S, HostingEvent> target;

        private InterceptingSubscriber(BoundedPublisherBridge<S, HostingEvent> target) {
            this.target = target;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (!sourceSubscription.compareAndSet(null, subscription)) {
                subscription.cancel();
                return;
            }
            target.onSubscribe(subscription);
        }

        @Override
        public void onNext(S item) {
            target.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            try {
                completeFailureOutcome(throwable);
            } finally {
                target.onComplete();
            }
        }

        @Override
        public void onComplete() {
            try {
                HostingOutcome outcome = Objects.requireNonNull(success.get(), "success outcome");
                if (!terminal.complete(outcome)) {
                    discardedOutcome.accept(outcome);
                }
            } catch (RuntimeException failure) {
                completeFailureOutcome(failure);
            } finally {
                target.onComplete();
            }
        }
    }

    private final class TerminalSubscriber implements Flow.Subscriber<HostingEvent> {
        private final Flow.Subscriber<? super HostingEvent> target;

        private TerminalSubscriber(Flow.Subscriber<? super HostingEvent> target) {
            this.target = target;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            target.onSubscribe(subscription);
        }

        @Override
        public void onNext(HostingEvent item) {
            target.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            try {
                completeFailureOutcome(throwable);
            } finally {
                target.onComplete();
            }
        }

        @Override
        public void onComplete() {
            target.onComplete();
        }
    }

    private void completeFailureOutcome(Throwable failure) {
        HostingOutcome outcome;
        try {
            outcome = Objects.requireNonNull(failureMapper.apply(failure), "failure outcome");
        } catch (RuntimeException mappingFailure) {
            try {
                outcome = Objects.requireNonNull(failureMapper.apply(mappingFailure), "fallback failure outcome");
            } catch (RuntimeException fallbackFailure) {
                terminal.completeExceptionally(fallbackFailure);
                return;
            }
        }
        if (!terminal.complete(outcome)) {
            discardedOutcome.accept(outcome);
        }
    }

    private static void reject(Flow.Subscriber<? super HostingEvent> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("This hosting run supports one subscriber."));
        } catch (RuntimeException ignored) {
            // Subscriber callbacks own their exceptions.
        }
    }

    private static void completeEmpty(Flow.Subscriber<? super HostingEvent> subscriber) {
        try {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onComplete();
        } catch (RuntimeException ignored) {
            // Subscriber callbacks own their exceptions.
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
