// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MCPEventPublisherTest {
    private static final int LARGE_BURST_SIZE = 50_000;

    @Test
    void drainsLargeBurstWithOneAtATimeReentrantDemandWithoutGrowingTheStack() {
        // Arrange
        MCPEventPublisher publisher = new MCPEventPublisher(LARGE_BURST_SIZE);
        MCPClientEvent event = event("burst");
        for (int index = 0; index < LARGE_BURST_SIZE; index++) {
            publisher.emit(event);
        }
        publisher.close();
        ReentrantSubscriber subscriber = new ReentrantSubscriber();

        // Act
        publisher.subscribe(subscriber);

        // Assert
        assertThat(subscriber.received).hasValue(LARGE_BURST_SIZE);
        assertThat(subscriber.maximumCallbackDepth).hasValue(1);
        assertThat(subscriber.completions).hasValue(1);
        assertThat(subscriber.failure).hasValue(null);
    }

    @Test
    void blockedReentrantSubscriberDoesNotHoldProducerStateLock() throws Exception {
        // Arrange
        MCPEventPublisher publisher = new MCPEventPublisher(2);
        CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        CountDownLatch secondCallbackSeen = new CountDownLatch(1);
        CountDownLatch secondEmitReturned = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicReference<Throwable> emitterFailure = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription nextSubscription) {
                subscription.set(nextSubscription);
                nextSubscription.request(1);
            }

            @Override
            public void onNext(MCPClientEvent item) {
                int current = received.incrementAndGet();
                subscription.get().request(1);
                if (current == 1) {
                    firstCallbackEntered.countDown();
                    awaitRelease(releaseFirstCallback);
                } else {
                    secondCallbackSeen.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                emitterFailure.compareAndSet(null, throwable);
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        Thread firstEmitter = Thread.startVirtualThread(() -> {
            try {
                publisher.emit(event("first"));
            } catch (RuntimeException failure) {
                emitterFailure.compareAndSet(null, failure);
            }
        });
        assertThat(firstCallbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread secondEmitter = Thread.startVirtualThread(() -> {
            try {
                publisher.emit(event("second"));
            } catch (RuntimeException failure) {
                emitterFailure.compareAndSet(null, failure);
            } finally {
                secondEmitReturned.countDown();
            }
        });

        // Act
        try {
            assertThat(secondEmitReturned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(firstEmitter.isAlive()).isTrue();
        } finally {
            releaseFirstCallback.countDown();
        }

        // Assert
        assertThat(secondCallbackSeen.await(5, TimeUnit.SECONDS)).isTrue();
        firstEmitter.join(5_000);
        secondEmitter.join(5_000);
        assertThat(firstEmitter.isAlive()).isFalse();
        assertThat(secondEmitter.isAlive()).isFalse();
        publisher.close();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasValue(2);
        assertThat(emitterFailure).hasValue(null);
    }

    @Test
    void serializesConcurrentCloseAndEmitWithExactlyOneTerminalSignal() throws Exception {
        for (int iteration = 0; iteration < 500; iteration++) {
            // Arrange
            MCPEventPublisher publisher = new MCPEventPublisher(1);
            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            subscriber.subscription.request(Long.MAX_VALUE);
            CountDownLatch start = new CountDownLatch(1);
            Thread emitter = Thread.startVirtualThread(() -> {
                awaitRelease(start);
                publisher.emit(event("race"));
            });
            Thread closer = Thread.startVirtualThread(() -> {
                awaitRelease(start);
                publisher.close();
            });

            // Act
            start.countDown();
            emitter.join(5_000);
            closer.join(5_000);

            // Assert
            assertThat(emitter.isAlive()).isFalse();
            assertThat(closer.isAlive()).isFalse();
            assertThat(subscriber.events).hasSizeLessThanOrEqualTo(1);
            assertThat(subscriber.terminalSignals).hasValue(1);
            assertThat(subscriber.completions).hasValue(1);
            assertThat(subscriber.failure).hasValue(null);
            int delivered = subscriber.events.size();
            publisher.emit(event("after-terminal"));
            publisher.close();
            subscriber.subscription.request(1);
            assertThat(subscriber.events).hasSize(delivered);
            assertThat(subscriber.terminalSignals).hasValue(1);
        }
    }

    @Test
    void rejectsInvalidDemandExactlyOnceAndDiscardsBufferedEvents() {
        for (long invalidDemand : List.of(0L, -1L)) {
            // Arrange
            MCPEventPublisher publisher = new MCPEventPublisher(2);
            RecordingSubscriber subscriber = new RecordingSubscriber();
            publisher.subscribe(subscriber);
            publisher.emit(event("buffered"));

            // Act
            subscriber.subscription.request(invalidDemand);

            // Assert
            assertThat(subscriber.failure)
                    .hasValueSatisfying(failure -> assertThat(failure)
                            .isInstanceOf(ValidationException.class)
                            .hasMessage("Flow demand must be positive."));
            assertThat(subscriber.events).isEmpty();
            assertThat(subscriber.terminalSignals).hasValue(1);
            subscriber.subscription.request(1);
            publisher.emit(event("ignored"));
            publisher.close();
            assertThat(subscriber.events).isEmpty();
            assertThat(subscriber.terminalSignals).hasValue(1);
        }
    }

    @Test
    void failsExactlyOnceWhenBackpressureBufferOverflows() {
        // Arrange
        MCPEventPublisher publisher = new MCPEventPublisher(2);
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        publisher.emit(event("one"));
        publisher.emit(event("two"));

        // Act
        publisher.emit(event("three"));

        // Assert
        assertThat(subscriber.failure)
                .hasValueSatisfying(failure -> assertThat(failure)
                        .isInstanceOf(MCPException.class)
                        .hasMessage(
                                "MCP event buffer overflowed at 2 items; consume events or increase maxEventBuffer."));
        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.terminalSignals).hasValue(1);
        subscriber.subscription.request(Long.MAX_VALUE);
        publisher.emit(event("ignored"));
        publisher.close();
        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.terminalSignals).hasValue(1);
    }

    @Test
    void honorsSaturatingDemandAndRejectsASecondSubscriber() {
        // Arrange
        MCPEventPublisher publisher = new MCPEventPublisher(2);
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();
        publisher.subscribe(first);
        first.subscription.request(Long.MAX_VALUE);
        first.subscription.request(Long.MAX_VALUE);

        // Act
        publisher.emit(event("one"));
        publisher.emit(event("two"));
        publisher.subscribe(second);
        publisher.close();

        // Assert
        assertThat(first.events).hasSize(2);
        assertThat(first.completions).hasValue(1);
        assertThat(second.failure)
                .hasValueSatisfying(failure -> assertThat(failure)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("one subscriber"));
        assertThat(second.terminalSignals).hasValue(1);
    }

    private static MCPClientEvent event(String message) {
        return new MCPClientEvent.Progress(StateValue.string(message), 0, 1.0, message);
    }

    private static void awaitRelease(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ReentrantSubscriber implements Flow.Subscriber<MCPClientEvent> {
        private final AtomicInteger received = new AtomicInteger();

        private final AtomicInteger maximumCallbackDepth = new AtomicInteger();

        private final AtomicInteger completions = new AtomicInteger();

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Flow.Subscription subscription;

        private int callbackDepth;

        @Override
        public void onSubscribe(Flow.Subscription nextSubscription) {
            subscription = nextSubscription;
            nextSubscription.request(1);
        }

        @Override
        public void onNext(MCPClientEvent item) {
            callbackDepth++;
            maximumCallbackDepth.accumulateAndGet(callbackDepth, Math::max);
            try {
                received.incrementAndGet();
                subscription.request(1);
            } finally {
                callbackDepth--;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<MCPClientEvent> {
        private final List<MCPClientEvent> events = new CopyOnWriteArrayList<>();

        private final AtomicInteger completions = new AtomicInteger();

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription nextSubscription) {
            subscription = nextSubscription;
        }

        @Override
        public void onNext(MCPClientEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            terminalSignals.incrementAndGet();
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
            terminalSignals.incrementAndGet();
        }
    }
}
