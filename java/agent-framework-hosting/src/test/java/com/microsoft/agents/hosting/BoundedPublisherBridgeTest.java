// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedPublisherBridgeTest {
    @Test
    void bridge_shouldPropagateIncrementalDemandAndCompleteExactlyOnce() {
        // Arrange
        ManualPublisher<Integer> source = new ManualPublisher<>();
        AtomicInteger cancellations = new AtomicInteger();
        BoundedPublisherBridge<Integer, String> bridge =
                new BoundedPublisherBridge<>(2, Object::toString, cancellations::incrementAndGet);
        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        subscriber.requestAfterEach = true;

        // Act
        bridge.subscribe(subscriber);
        source.subscribe(bridge);
        subscriber.subscription.request(1);
        source.emit(1);
        source.emit(2);
        source.complete();
        source.complete();

        // Assert
        assertThat(subscriber.terminal.orTimeout(5, TimeUnit.SECONDS).join()).containsExactly("1", "2");
        assertThat(subscriber.terminalSignals).hasValue(1);
        assertThat(source.requested).hasValue(3);
        assertThat(cancellations).hasValue(0);
    }

    @Test
    void bridge_shouldRejectInvalidDemandAndCancelOnce() {
        // Arrange
        ManualPublisher<Integer> source = new ManualPublisher<>();
        AtomicInteger cancellations = new AtomicInteger();
        BoundedPublisherBridge<Integer, Integer> bridge =
                new BoundedPublisherBridge<>(2, value -> value, cancellations::incrementAndGet);
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        bridge.subscribe(subscriber);
        source.subscribe(bridge);

        // Act
        subscriber.subscription.request(0);
        subscriber.subscription.request(-1);

        // Assert
        assertThat(subscriber.failure.orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(source.cancelled).isTrue();
        assertThat(cancellations).hasValue(1);
        assertThat(subscriber.terminalSignals).hasValue(1);
    }

    @Test
    void bridge_shouldBoundNonCompliantProducerAndCancelOverflow() {
        // Arrange
        ManualPublisher<Integer> source = new ManualPublisher<>();
        AtomicInteger cancellations = new AtomicInteger();
        BoundedPublisherBridge<Integer, Integer> bridge =
                new BoundedPublisherBridge<>(2, value -> value, cancellations::incrementAndGet);
        RecordingSubscriber<Integer> subscriber = new RecordingSubscriber<>();
        bridge.subscribe(subscriber);
        source.subscribe(bridge);

        // Act
        source.emit(1);
        source.emit(2);
        source.emit(3);

        // Assert
        assertThat(subscriber.failure.orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(HostingStreamOverflowException.class);
        assertThat(source.cancelled).isTrue();
        assertThat(cancellations).hasValue(1);
        assertThat(subscriber.items).isEmpty();
        assertThat(subscriber.terminalSignals).hasValue(1);
    }

    @Test
    void bridge_shouldRejectSecondSubscriberWithoutAffectingFirst() {
        // Arrange
        BoundedPublisherBridge<Integer, Integer> bridge = new BoundedPublisherBridge<>(1, value -> value, () -> {});
        RecordingSubscriber<Integer> first = new RecordingSubscriber<>();
        RecordingSubscriber<Integer> second = new RecordingSubscriber<>();

        // Act
        bridge.subscribe(first);
        bridge.subscribe(second);

        // Assert
        assertThat(second.failure.orTimeout(5, TimeUnit.SECONDS).join()).isInstanceOf(IllegalStateException.class);
        assertThat(first.terminalSignals).hasValue(0);
    }

    private static final class ManualPublisher<T> implements Flow.Publisher<T> {
        private final AtomicLong requested = new AtomicLong();

        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Flow.Subscriber<? super T> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super T> value) {
            subscriber = value;
            value.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    requested.updateAndGet(current -> {
                        long sum = current + count;
                        return sum < 0 ? Long.MAX_VALUE : sum;
                    });
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }

        private void emit(T item) {
            subscriber.onNext(item);
        }

        private void complete() {
            subscriber.onComplete();
        }
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        private final ArrayList<T> items = new ArrayList<>();

        private final CompletableFuture<List<T>> terminal = new CompletableFuture<>();

        private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private Flow.Subscription subscription;

        private boolean requestAfterEach;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
        }

        @Override
        public void onNext(T item) {
            items.add(item);
            if (requestAfterEach) {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            terminalSignals.incrementAndGet();
            failure.complete(throwable);
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            terminal.complete(List.copyOf(items));
        }
    }
}
