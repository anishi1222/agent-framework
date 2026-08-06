// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SingleSubscriberPublisherTest {
    @Test
    void terminalSignal_shouldReleaseSubscriber_afterReentrantRequest() {
        // Arrange
        SingleSubscriberPublisher<String> publisher = new SingleSubscriberPublisher<>(() -> {}, () -> {}, 1);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
            }

            @Override
            public void onNext(String item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {
                completions.incrementAndGet();
                subscription.get().request(1);
            }
        });
        assertThat(publisher.hasSubscriber()).isTrue();

        // Act
        publisher.complete();

        // Assert
        assertThat(completions).hasValue(1);
        assertThat(publisher.hasSubscriber()).isFalse();
    }

    @Test
    void cancellationBySubscriber_shouldReleaseSubscriberAndBufferedUpdates() {
        // Arrange
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        SingleSubscriberPublisher<String> publisher =
                new SingleSubscriberPublisher<>(() -> {}, cancellations::incrementAndGet, 1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
            }

            @Override
            public void onNext(String item) {}

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        publisher.emit("retained");

        // Act
        subscription.get().cancel();

        // Assert
        assertThat(publisher.hasSubscriber()).isFalse();
        assertThat(publisher.bufferedUpdateCount()).isZero();
        assertThat(cancellations).hasValue(1);
    }
}
