// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class TelemetryPublisher<T> implements Flow.Publisher<T> {
    private final Supplier<TelemetryOperation> operationFactory;

    private final Supplier<Flow.Publisher<T>> sourceFactory;

    private final Consumer<T> itemObserver;

    private final Runnable terminalObserver;

    private final RunCancellation cancellation;

    private final AtomicBoolean subscribed = new AtomicBoolean();

    TelemetryPublisher(
            Supplier<TelemetryOperation> operationFactory,
            Supplier<Flow.Publisher<T>> sourceFactory,
            Consumer<T> itemObserver,
            RunCancellation cancellation) {
        this(operationFactory, sourceFactory, itemObserver, () -> {}, cancellation);
    }

    TelemetryPublisher(
            Supplier<TelemetryOperation> operationFactory,
            Supplier<Flow.Publisher<T>> sourceFactory,
            Consumer<T> itemObserver,
            Runnable terminalObserver,
            RunCancellation cancellation) {
        this.operationFactory = Objects.requireNonNull(operationFactory, "operationFactory");
        this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
        this.itemObserver = Objects.requireNonNull(itemObserver, "itemObserver");
        this.terminalObserver = Objects.requireNonNull(terminalObserver, "terminalObserver");
        this.cancellation = cancellation;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new EmptySubscription());
            subscriber.onError(new IllegalStateException("This telemetry publisher supports one subscriber."));
            return;
        }

        TelemetryOperation operation;
        try {
            operation = Objects.requireNonNull(operationFactory.get(), "operationFactory returned null.");
        } catch (Throwable instrumentationFailure) {
            subscribeWithoutTelemetry(subscriber);
            return;
        }

        AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();
        TelemetryTermination termination = new TelemetryTermination(operation, terminalObserver);
        ObservingSubscriber<T> observing =
                new ObservingSubscriber<>(subscriber, operation, itemObserver, termination, cancellationRegistration);
        if (cancellation != null) {
            try {
                cancellationRegistration.set(RunCancellations.register(cancellation, () -> {
                    observing.externalCancelled();
                    closeRegistration(cancellationRegistration, operation);
                }));
            } catch (Throwable instrumentationFailure) {
                operation.instrumentationFailure(instrumentationFailure);
            }
        }

        try {
            Flow.Publisher<T> source = Objects.requireNonNull(
                    operation.callWithContext(sourceFactory), "Instrumented operation returned a null publisher.");
            operation.runWithContext(() -> source.subscribe(observing));
        } catch (Throwable failure) {
            observing.subscribeFailed(failure);
        }
    }

    private void subscribeWithoutTelemetry(Flow.Subscriber<? super T> subscriber) {
        AtomicBoolean downstreamSubscribed = new AtomicBoolean();
        AtomicBoolean terminated = new AtomicBoolean();
        try {
            Flow.Publisher<T> source =
                    Objects.requireNonNull(sourceFactory.get(), "Instrumented operation returned a null publisher.");
            source.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    downstreamSubscribed.set(true);
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(T item) {
                    if (!terminated.get()) {
                        subscriber.onNext(item);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    if (terminated.compareAndSet(false, true)) {
                        subscriber.onError(throwable);
                    }
                }

                @Override
                public void onComplete() {
                    if (terminated.compareAndSet(false, true)) {
                        subscriber.onComplete();
                    }
                }
            });
        } catch (Throwable failure) {
            if (terminated.compareAndSet(false, true)) {
                if (!downstreamSubscribed.get()) {
                    subscriber.onSubscribe(new EmptySubscription());
                }
                subscriber.onError(failure);
            }
        }
    }

    private static void closeRegistration(
            AtomicReference<RunCancellationRegistration> cancellationRegistration, TelemetryOperation operation) {
        RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
        if (registration != null) {
            try {
                registration.close();
            } catch (Throwable failure) {
                operation.instrumentationFailure(failure);
            }
        }
    }

    private static final class TelemetryTermination {
        private final TelemetryOperation operation;

        private final Runnable observer;

        private boolean finished;

        private TelemetryTermination(TelemetryOperation operation, Runnable observer) {
            this.operation = operation;
            this.observer = observer;
        }

        private synchronized void completed() {
            if (!finished) {
                finished = true;
                operation.observeInstrumentation(observer);
                operation.success();
            }
        }

        private synchronized void failed(Throwable failure) {
            if (!finished) {
                finished = true;
                operation.observeInstrumentation(observer);
                operation.failure(failure);
            }
        }

        private synchronized void cancelled() {
            if (!finished) {
                finished = true;
                operation.observeInstrumentation(observer);
                operation.cancelled();
            }
        }

        private synchronized void cancelledUnlessTerminated(AtomicBoolean downstreamTerminated) {
            if (!finished && !downstreamTerminated.get()) {
                finished = true;
                operation.observeInstrumentation(observer);
                operation.cancelled();
            }
        }
    }

    private static final class ObservingSubscriber<T> implements Flow.Subscriber<T> {
        private final Flow.Subscriber<? super T> downstream;

        private final TelemetryOperation operation;

        private final Consumer<T> itemObserver;

        private final TelemetryTermination termination;

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration;

        private final AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();

        private final AtomicBoolean downstreamSubscribed = new AtomicBoolean();

        private final AtomicBoolean terminated = new AtomicBoolean();

        private ObservingSubscriber(
                Flow.Subscriber<? super T> downstream,
                TelemetryOperation operation,
                Consumer<T> itemObserver,
                TelemetryTermination termination,
                AtomicReference<RunCancellationRegistration> cancellationRegistration) {
            this.downstream = downstream;
            this.operation = operation;
            this.itemObserver = itemObserver;
            this.termination = termination;
            this.cancellationRegistration = cancellationRegistration;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Flow.Subscription checked = Objects.requireNonNull(subscription, "subscription");
            if (!upstream.compareAndSet(null, checked)) {
                checked.cancel();
                operation.instrumentationFailure(
                        new IllegalStateException("Instrumented publisher called onSubscribe more than once."));
                return;
            }
            Flow.Subscription wrapped = new Flow.Subscription() {
                @Override
                public void request(long count) {
                    operation.runWithContext(() -> checked.request(count));
                }

                @Override
                public void cancel() {
                    if (terminated.compareAndSet(false, true)) {
                        closeRegistration(cancellationRegistration, operation);
                        termination.cancelled();
                        operation.runWithContext(checked::cancel);
                    }
                }
            };
            downstreamSubscribed.set(true);
            try {
                operation.runWithContext(() -> downstream.onSubscribe(wrapped));
            } catch (Throwable failure) {
                if (terminated.compareAndSet(false, true)) {
                    closeRegistration(cancellationRegistration, operation);
                    termination.failed(failure);
                    cancelUpstream(checked);
                }
            }
        }

        @Override
        public void onNext(T item) {
            if (terminated.get()) {
                return;
            }
            operation.observeInstrumentation(() -> itemObserver.accept(item));
            try {
                operation.runWithContext(() -> downstream.onNext(item));
            } catch (Throwable failure) {
                if (terminated.compareAndSet(false, true)) {
                    closeRegistration(cancellationRegistration, operation);
                    termination.failed(failure);
                    cancelUpstream(upstream.get());
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            closeRegistration(cancellationRegistration, operation);
            termination.failed(throwable);
            operation.runWithContext(() -> downstream.onError(throwable));
        }

        @Override
        public void onComplete() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            closeRegistration(cancellationRegistration, operation);
            termination.completed();
            operation.runWithContext(downstream::onComplete);
        }

        private void subscribeFailed(Throwable failure) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            closeRegistration(cancellationRegistration, operation);
            termination.failed(failure);
            if (downstreamSubscribed.compareAndSet(false, true)) {
                operation.runWithContext(() -> downstream.onSubscribe(new EmptySubscription()));
            }
            operation.runWithContext(() -> downstream.onError(failure));
        }

        private void externalCancelled() {
            termination.cancelledUnlessTerminated(terminated);
        }

        private void cancelUpstream(Flow.Subscription subscription) {
            if (subscription == null) {
                return;
            }
            try {
                operation.runWithContext(subscription::cancel);
            } catch (Throwable failure) {
                operation.instrumentationFailure(failure);
            }
        }
    }

    private static final class EmptySubscription implements Flow.Subscription {
        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }
}
