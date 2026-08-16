// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.TextContent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatKitStreamingPublisherTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-14T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(CREATED_AT, ZoneOffset.UTC);

    @Test
    void publisher_shouldHonorExactDemandAndEmitAddedDeltasThenDone() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher();
        AtomicInteger factoryCalls = new AtomicInteger();
        ChatKitStreamingPublisher publisher = publisher(
                () -> {
                    factoryCalls.incrementAndGet();
                    return source;
                },
                8);
        RecordingSubscriber subscriber = new RecordingSubscriber();

        // Act and assert: subscription is cold and does not prefetch without demand.
        assertThat(factoryCalls).hasValue(0);
        publisher.subscribe(subscriber);
        assertThat(factoryCalls).hasValue(1);
        assertThat(source.subscription.requested).isZero();

        subscriber.request(1);
        assertThat(source.subscription.requested).isEqualTo(1);
        source.emit(update(
                new TextContent("Hel"),
                new DataContent(new byte[] {1}, "application/octet-stream"),
                new TextContent("lo")));

        assertThat(subscriber.events).singleElement().isInstanceOf(ChatKitThreadItemAddedEvent.class);
        assertThat(source.subscription.requested).isZero();

        subscriber.request(2);
        assertThat(subscriber.events)
                .containsExactly(
                        added(),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "Hel"),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "lo"));
        assertThat(source.subscription.requested).isZero();

        source.complete();
        assertThat(subscriber.completed).isFalse();
        subscriber.request(1);

        assertThat(subscriber.events)
                .containsExactly(
                        added(),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "Hel"),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "lo"),
                        done("Hello"));
        assertThat(subscriber.completed).isTrue();
        assertThat(subscriber.error).isNull();
    }

    @Test
    void publisher_shouldPreserveOrderedTextAcrossMultipleUpdatesWithoutExtraThreads() {
        // Arrange
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        Thread caller = Thread.currentThread();
        subscriber.captureSignalThreads = true;
        publisher(() -> source, 8).subscribe(subscriber);

        // Act
        subscriber.request(Long.MAX_VALUE);
        source.emit(update(new TextContent("A")));
        source.emit(update(new TextContent("B"), new TextContent("C")));
        source.complete();

        // Assert
        assertThat(subscriber.events)
                .containsExactly(
                        added(),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "A"),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "B"),
                        new ChatKitThreadItemUpdatedEvent("msg_test", 0, "C"),
                        done("ABC"));
        assertThat(subscriber.signalThreads).allMatch(thread -> thread == caller);
        assertThat(source.subscription.maxSingleRequest).isEqualTo(1);
        assertThat(subscriber.completed).isTrue();
    }

    @Test
    void publisher_shouldEmitNothingForEmptySource() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);

        subscriber.request(1);
        source.complete();

        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.completed).isTrue();
        assertThat(subscriber.error).isNull();
    }

    @Test
    void publisher_shouldAddAndCompleteItemForNonTextUpdate() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);

        subscriber.request(Long.MAX_VALUE);
        source.emit(update(new DataContent(new byte[] {1}, "application/octet-stream")));
        source.complete();

        assertThat(subscriber.events).containsExactly(added(), done());
        assertThat(subscriber.completed).isTrue();
    }

    @Test
    void publisher_shouldForwardCancellationAndDropPendingEvents() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);
        subscriber.request(1);
        source.emit(update(new TextContent("pending")));

        subscriber.cancel();
        subscriber.request(10);
        source.complete();

        assertThat(source.subscription.cancelCalls).isEqualTo(1);
        assertThat(subscriber.events).containsExactly(added());
        assertThat(subscriber.completed).isFalse();
        assertThat(subscriber.error).isNull();
    }

    @Test
    void publisher_shouldAvoidSourceCreationWhenCancelledInOnSubscribe() {
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        subscriber.cancelOnSubscribe = true;
        publisher(
                        () -> {
                            factoryCalls.incrementAndGet();
                            return new ControlledPublisher();
                        },
                        8)
                .subscribe(subscriber);

        assertThat(factoryCalls).hasValue(0);
        assertThat(subscriber.events).isEmpty();
    }

    @Test
    void publisher_shouldRecheckCancellationBeforeInvokingSourceFactory() throws InterruptedException {
        // Arrange
        CountDownLatch idStarted = new CountDownLatch(1);
        CountDownLatch releaseId = new CountDownLatch(1);
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        ChatKitStreamingPublisher publisher = new ChatKitStreamingPublisher(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new ControlledPublisher();
                },
                "thread-1",
                () -> {
                    idStarted.countDown();
                    await(releaseId);
                    return "msg_test";
                },
                CLOCK,
                8);
        Thread worker = Thread.ofPlatform().start(() -> publisher.subscribe(subscriber));

        // Act
        assertThat(idStarted.await(5, TimeUnit.SECONDS)).isTrue();
        subscriber.cancel();
        releaseId.countDown();
        worker.join(5_000);

        // Assert
        assertThat(worker.isAlive()).isFalse();
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void publisher_shouldRejectInvalidDemandBeforeCreatingSource() {
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        subscriber.invalidRequestOnSubscribe = true;
        publisher(
                        () -> {
                            factoryCalls.incrementAndGet();
                            return new ControlledPublisher();
                        },
                        8)
                .subscribe(subscriber);

        assertThat(factoryCalls).hasValue(0);
        assertThat(subscriber.error)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Demand must be positive.");
    }

    @Test
    void publisher_shouldPropagateSourceErrorWithoutItemDone() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE);
        source.emit(update(new TextContent("partial")));

        source.fail(new IllegalStateException("source failed"));

        assertThat(subscriber.events)
                .containsExactly(added(), new ChatKitThreadItemUpdatedEvent("msg_test", 0, "partial"));
        assertThat(subscriber.error).isInstanceOf(IllegalStateException.class).hasMessage("source failed");
        assertThat(subscriber.completed).isFalse();
    }

    @Test
    void publisher_shouldDropPendingEventsAndSignalSourceErrorWithoutMoreDemand() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);
        subscriber.request(1);
        source.emit(update(new TextContent("pending-one"), new TextContent("pending-two")));

        source.fail(new IllegalStateException("source failed"));

        assertThat(subscriber.events).containsExactly(added());
        assertThat(subscriber.error).isInstanceOf(IllegalStateException.class).hasMessage("source failed");
        assertThat(source.subscription.cancelCalls).isEqualTo(1);
    }

    @Test
    void publisher_shouldRejectSecondSubscriberWithoutResubscribingSource() {
        ControlledPublisher source = new ControlledPublisher();
        AtomicInteger factoryCalls = new AtomicInteger();
        ChatKitStreamingPublisher publisher = publisher(
                () -> {
                    factoryCalls.incrementAndGet();
                    return source;
                },
                8);
        RecordingSubscriber first = new RecordingSubscriber();
        RecordingSubscriber second = new RecordingSubscriber();

        publisher.subscribe(first);
        publisher.subscribe(second);

        assertThat(factoryCalls).hasValue(1);
        assertThat(second.error).isInstanceOf(IllegalStateException.class).hasMessageContaining("one subscriber");
        assertThat(second.events).isEmpty();
    }

    @Test
    void publisher_shouldCancelSourceWhenOneUpdateExceedsBufferBound() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 3).subscribe(subscriber);
        subscriber.request(1);

        source.emit(update(new TextContent("one"), new TextContent("two")));

        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.error).isInstanceOf(IllegalStateException.class).hasMessageContaining("bounded");
        assertThat(source.subscription.cancelCalls).isEqualTo(1);
    }

    @Test
    void publisher_shouldRejectSourceUpdateWithoutDemand() {
        ControlledPublisher source = new ControlledPublisher();
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);

        source.emitIgnoringDemand(update(new TextContent("invalid")));

        assertThat(subscriber.events).isEmpty();
        assertThat(subscriber.error).isInstanceOf(IllegalStateException.class).hasMessageContaining("without demand");
        assertThat(source.subscription.cancelCalls).isEqualTo(1);
    }

    @Test
    void publisher_shouldSignalSourceFactoryFailure() {
        RecordingSubscriber subscriber = new RecordingSubscriber();
        publisher(
                        () -> {
                            throw new IllegalStateException("factory failed");
                        },
                        8)
                .subscribe(subscriber);

        assertThat(subscriber.error).isInstanceOf(IllegalStateException.class).hasMessage("factory failed");
        assertThat(subscriber.events).isEmpty();
    }

    @Test
    void publisher_shouldCancelUpstreamWhenDownstreamOnNextThrows() {
        ControlledPublisher source = new ControlledPublisher();
        ThrowingSubscriber subscriber = new ThrowingSubscriber();
        publisher(() -> source, 8).subscribe(subscriber);

        subscriber.subscription.request(Long.MAX_VALUE);
        source.emit(update(new TextContent("value")));

        assertThat(source.subscription.cancelCalls).isEqualTo(1);
        assertThat(subscriber.onNextCalls).isEqualTo(1);
    }

    private static ChatKitStreamingPublisher publisher(
            java.util.function.Supplier<? extends Flow.Publisher<AgentResponseUpdate>> sourceFactory,
            int maxBufferedEvents) {
        return new ChatKitStreamingPublisher(sourceFactory, "thread-1", () -> "msg_test", CLOCK, maxBufferedEvents);
    }

    private static AgentResponseUpdate update(Content... contents) {
        return AgentResponseUpdate.builder().contents(List.of(contents)).build();
    }

    private static ChatKitThreadItemAddedEvent added() {
        return new ChatKitThreadItemAddedEvent(
                new ChatKitAssistantMessageItem("msg_test", "thread-1", List.of(), CREATED_AT));
    }

    private static ChatKitThreadItemDoneEvent done(String... text) {
        return new ChatKitThreadItemDoneEvent(
                new ChatKitAssistantMessageItem("msg_test", "thread-1", List.of(text), CREATED_AT));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test coordination was interrupted.", exception);
        }
    }

    private static final class ControlledPublisher implements Flow.Publisher<AgentResponseUpdate> {
        private final ControlledSubscription subscription = new ControlledSubscription();
        private Flow.Subscriber<? super AgentResponseUpdate> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super AgentResponseUpdate> value) {
            subscriber = value;
            value.onSubscribe(subscription);
        }

        private void emit(AgentResponseUpdate update) {
            if (subscription.requested <= 0) {
                throw new IllegalStateException("Test source has no demand.");
            }
            subscription.requested--;
            subscriber.onNext(update);
        }

        private void emitIgnoringDemand(AgentResponseUpdate update) {
            subscriber.onNext(update);
        }

        private void complete() {
            subscriber.onComplete();
        }

        private void fail(Throwable failure) {
            subscriber.onError(failure);
        }
    }

    private static final class ControlledSubscription implements Flow.Subscription {
        private long requested;
        private long maxSingleRequest;
        private int cancelCalls;

        @Override
        public void request(long count) {
            maxSingleRequest = Math.max(maxSingleRequest, count);
            requested = addCap(requested, count);
        }

        @Override
        public void cancel() {
            cancelCalls++;
            requested = 0;
        }

        private static long addCap(long current, long increment) {
            long result = current + increment;
            return result < 0 ? Long.MAX_VALUE : result;
        }
    }

    private static class RecordingSubscriber implements Flow.Subscriber<ChatKitThreadEvent> {
        private final ArrayList<ChatKitThreadEvent> events = new ArrayList<>();
        private final ArrayList<Thread> signalThreads = new ArrayList<>();
        private Flow.Subscription subscription;
        private Throwable error;
        private boolean completed;
        private boolean cancelOnSubscribe;
        private boolean invalidRequestOnSubscribe;
        private boolean captureSignalThreads;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (cancelOnSubscribe) {
                value.cancel();
            } else if (invalidRequestOnSubscribe) {
                value.request(0);
            }
        }

        @Override
        public void onNext(ChatKitThreadEvent item) {
            events.add(item);
            if (captureSignalThreads) {
                signalThreads.add(Thread.currentThread());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            if (captureSignalThreads) {
                signalThreads.add(Thread.currentThread());
            }
        }

        @Override
        public void onComplete() {
            completed = true;
            if (captureSignalThreads) {
                signalThreads.add(Thread.currentThread());
            }
        }

        private void request(long count) {
            subscription.request(count);
        }

        private void cancel() {
            subscription.cancel();
        }
    }

    private static final class ThrowingSubscriber implements Flow.Subscriber<ChatKitThreadEvent> {
        private Flow.Subscription subscription;
        private int onNextCalls;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
        }

        @Override
        public void onNext(ChatKitThreadEvent item) {
            onNextCalls++;
            throw new IllegalStateException("downstream failure");
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }
}
