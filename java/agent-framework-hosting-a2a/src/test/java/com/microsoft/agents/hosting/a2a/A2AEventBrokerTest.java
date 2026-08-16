// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.protocols.a2a.A2AStreamEvent;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import com.microsoft.agents.protocols.a2a.TaskStatus;
import com.microsoft.agents.protocols.a2a.TaskStatusUpdateEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class A2AEventBrokerTest {
    private static final A2APrincipal PRINCIPAL = new A2APrincipal("principal", "isolation");

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void subscribers_shouldReceiveCurrentTaskFirstAndIndependentOrderedEvents() throws Exception {
        // Arrange
        A2AEventBroker broker = new A2AEventBroker(10, 10);
        Task submitted = task(TaskState.TASK_STATE_SUBMITTED, 0);
        broker.register(PRINCIPAL, submitted);
        Subscriber first = new Subscriber(Long.MAX_VALUE);
        Subscriber second = new Subscriber(Long.MAX_VALUE);
        broker.subscribe(PRINCIPAL, submitted.id()).subscribe(first);
        broker.subscribe(PRINCIPAL, submitted.id()).subscribe(second);
        Task working = task(TaskState.TASK_STATE_WORKING, 1);
        Task completed = task(TaskState.TASK_STATE_COMPLETED, 2);

        // Act
        broker.publish(
                PRINCIPAL,
                working,
                new TaskStatusUpdateEvent(working.id(), working.contextId(), working.status(), Map.of()));
        broker.publish(
                PRINCIPAL,
                completed,
                new TaskStatusUpdateEvent(completed.id(), completed.contextId(), completed.status(), Map.of()));
        first.completed().get(5, TimeUnit.SECONDS);
        second.completed().get(5, TimeUnit.SECONDS);

        // Assert
        assertThat(first.events()).hasSize(3);
        assertThat(second.events()).containsExactlyElementsOf(first.events());
        assertThat(first.events().getFirst()).isEqualTo(submitted);
        assertThat(first.terminalSignals()).isEqualTo(1);
        assertThat(second.terminalSignals()).isEqualTo(1);
    }

    @Test
    void slowSubscriber_shouldFailOnConfiguredBufferOverflow() throws Exception {
        // Arrange
        A2AEventBroker broker = new A2AEventBroker(10, 1);
        Task submitted = task(TaskState.TASK_STATE_SUBMITTED, 0);
        broker.register(PRINCIPAL, submitted);
        Subscriber subscriber = new Subscriber(0);
        broker.subscribe(PRINCIPAL, submitted.id()).subscribe(subscriber);

        // Act
        Task working = task(TaskState.TASK_STATE_WORKING, 1);
        broker.publish(
                PRINCIPAL,
                working,
                new TaskStatusUpdateEvent(working.id(), working.contextId(), working.status(), Map.of()));
        subscriber.request(1);

        // Assert
        Throwable failure = subscriber.failed().get(5, TimeUnit.SECONDS);
        assertThat(failure)
                .isInstanceOf(com.microsoft.agents.protocols.a2a.A2ATransportException.class)
                .hasMessageContaining("maxBufferedEvents=1");
    }

    @Test
    void repeatedTerminalSnapshots_shouldDeliverOnceAndReleaseChannelCapacity() throws Exception {
        // Arrange
        A2AEventBroker broker = new A2AEventBroker(1, 2);

        // Act / Assert
        for (int index = 0; index < 20; index++) {
            Task terminal = task("terminal-" + index, TaskState.TASK_STATE_COMPLETED, index);
            for (int duplicate = 0; duplicate < 2; duplicate++) {
                broker.register(PRINCIPAL, terminal);
                Subscriber subscriber = new Subscriber(Long.MAX_VALUE);
                broker.subscribe(PRINCIPAL, terminal.id()).subscribe(subscriber);
                subscriber.completed().get(5, TimeUnit.SECONDS);
                assertThat(subscriber.events()).containsExactly(terminal);
                assertThat(subscriber.terminalSignals()).isEqualTo(1);
            }
        }
    }

    @Test
    void repeatedInterruptedSnapshots_shouldResubscribeWithoutLeakingChannelCapacity() throws Exception {
        // Arrange
        A2AEventBroker broker = new A2AEventBroker(1, 2);

        // Act / Assert
        for (int index = 0; index < 20; index++) {
            TaskState state = index % 2 == 0 ? TaskState.TASK_STATE_INPUT_REQUIRED : TaskState.TASK_STATE_AUTH_REQUIRED;
            Task interrupted = task("interrupted-" + index, state, index);
            broker.register(PRINCIPAL, interrupted);
            Subscriber subscriber = new Subscriber(Long.MAX_VALUE);
            broker.subscribe(PRINCIPAL, interrupted.id()).subscribe(subscriber);
            subscriber.completed().get(5, TimeUnit.SECONDS);
            assertThat(subscriber.events()).containsExactly(interrupted);
            assertThat(subscriber.terminalSignals()).isEqualTo(1);
        }
    }

    @Test
    void completedSnapshotRemoval_shouldNotRemoveConcurrentReplacementChannel() throws Exception {
        // Arrange
        A2AEventBroker broker = new A2AEventBroker(1, 4);
        Task completed = task("task", TaskState.TASK_STATE_COMPLETED, 1);
        broker.register(PRINCIPAL, completed);
        Flow.Publisher<A2AStreamEvent> completedPublisher = broker.subscribe(PRINCIPAL, completed.id());
        BlockingSubscriber oldSubscriber = new BlockingSubscriber();
        CompletableFuture<Void> subscribing =
                CompletableFuture.runAsync(() -> completedPublisher.subscribe(oldSubscriber));
        oldSubscriber.awaitOnSubscribe();
        Task replacement = task("task", TaskState.TASK_STATE_WORKING, 2);

        // Act
        broker.register(PRINCIPAL, replacement);
        oldSubscriber.releaseOnSubscribe();
        subscribing.get(5, TimeUnit.SECONDS);
        oldSubscriber.completed().get(5, TimeUnit.SECONDS);
        Subscriber replacementSubscriber = new Subscriber(Long.MAX_VALUE);
        broker.subscribe(PRINCIPAL, replacement.id()).subscribe(replacementSubscriber);
        Task terminal = task("task", TaskState.TASK_STATE_COMPLETED, 3);
        broker.publish(
                PRINCIPAL,
                terminal,
                new TaskStatusUpdateEvent(terminal.id(), terminal.contextId(), terminal.status(), Map.of()));
        replacementSubscriber.completed().get(5, TimeUnit.SECONDS);

        // Assert
        assertThat(oldSubscriber.events()).containsExactly(completed);
        assertThat(oldSubscriber.terminalSignals()).isEqualTo(1);
        assertThat(replacementSubscriber.events().getFirst()).isEqualTo(replacement);
        assertThat(replacementSubscriber.events()).hasSize(2);
        assertThat(replacementSubscriber.terminalSignals()).isEqualTo(1);
    }

    private static Task task(TaskState state, long seconds) {
        return task("task", state, seconds);
    }

    private static Task task(String id, TaskState state, long seconds) {
        return Task.builder(id, "context", new TaskStatus(state, NOW.plusSeconds(seconds)))
                .build();
    }

    private static final class Subscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final List<A2AStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        private final CompletableFuture<Throwable> failed = new CompletableFuture<>();

        private final long initialDemand;

        private final AtomicInteger terminalSignals = new AtomicInteger();

        private Flow.Subscription subscription;

        private Subscriber(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (initialDemand > 0) {
                value.request(initialDemand);
            }
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminalSignals.incrementAndGet();
            failed.complete(throwable);
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            completed.complete(null);
        }

        private void request(long count) {
            subscription.request(count);
        }

        private List<A2AStreamEvent> events() {
            return List.copyOf(events);
        }

        private CompletableFuture<Void> completed() {
            return completed;
        }

        private CompletableFuture<Throwable> failed() {
            return failed;
        }

        private int terminalSignals() {
            return terminalSignals.get();
        }
    }

    private static final class BlockingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final List<A2AStreamEvent> events = new CopyOnWriteArrayList<>();

        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        private final CountDownLatch subscribed = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        private final AtomicInteger terminalSignals = new AtomicInteger();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscribed.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    completed.completeExceptionally(new IllegalStateException("Timed out awaiting test release."));
                    return;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                completed.completeExceptionally(failure);
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminalSignals.incrementAndGet();
            completed.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminalSignals.incrementAndGet();
            completed.complete(null);
        }

        private void awaitOnSubscribe() throws InterruptedException {
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void releaseOnSubscribe() {
            release.countDown();
        }

        private List<A2AStreamEvent> events() {
            return List.copyOf(events);
        }

        private CompletableFuture<Void> completed() {
            return completed;
        }

        private int terminalSignals() {
            return terminalSignals.get();
        }
    }
}
