// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalOutcomePublisher implements Flow.Publisher<HostingEvent> {
    private final CompletionStage<HostingOutcome> terminal;

    private final Runnable cancellation;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    TerminalOutcomePublisher(CompletionStage<HostingOutcome> terminal, Runnable cancellation) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super HostingEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            reject(subscriber);
            return;
        }
        EmptyRunSubscription subscription = new EmptyRunSubscription(subscriber);
        try {
            subscriber.onSubscribe(subscription);
        } catch (RuntimeException ignored) {
            subscription.cancel();
            return;
        }
        terminal.whenComplete((outcome, failure) -> subscription.complete());
    }

    private static void reject(Flow.Subscriber<? super HostingEvent> subscriber) {
        try {
            subscriber.onSubscribe(NoopSubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("This hosting run supports one subscriber."));
        } catch (RuntimeException ignored) {
            // Subscriber callbacks own their exceptions.
        }
    }

    private final class EmptyRunSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super HostingEvent> subscriber;

        private final AtomicBoolean done = new AtomicBoolean();

        private EmptyRunSubscription(Flow.Subscriber<? super HostingEvent> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long count) {
            if (count <= 0 && done.compareAndSet(false, true)) {
                cancellation.run();
                try {
                    subscriber.onError(new IllegalArgumentException("Flow demand must be positive."));
                } catch (RuntimeException ignored) {
                    // Subscriber callbacks own their exceptions.
                }
            }
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                cancellation.run();
            }
        }

        private void complete() {
            if (done.compareAndSet(false, true)) {
                try {
                    subscriber.onComplete();
                } catch (RuntimeException ignored) {
                    // Subscriber callbacks own their exceptions.
                }
            }
        }
    }

    private enum NoopSubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
