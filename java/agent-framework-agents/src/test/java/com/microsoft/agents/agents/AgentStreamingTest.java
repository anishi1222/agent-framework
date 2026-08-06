// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.TextContent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentStreamingTest {
    @Test
    void stream_shouldNotDeliverWithoutDemand_andShouldDrainInDemandOrder() throws Exception {
        // Arrange
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming(List.of(update(0, "first", null), update(1, "second", FinishReason.STOP)));
        DemandSubscriber subscriber = new DemandSubscriber(0);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello").subscribe(subscriber);
            assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(subscriber.updates()).isEmpty();
            assertThat(subscriber.terminal().isDone()).isFalse();

            subscriber.request(1);
            assertEventually(() -> subscriber.updates().size() == 1);
            assertThat(subscriber.terminal().isDone()).isFalse();

            subscriber.request(1);
            subscriber.terminal().join();
        }

        // Assert
        assertThat(subscriber.updates()).extracting(AgentResponseUpdate::text).containsExactly("first", "second");
        assertThat(subscriber.completions()).hasValue(1);
        assertThat(subscriber.errors()).isEmpty();
    }

    @Test
    void stream_shouldRejectInvalidDemandOnce_andCancelBeforeProviderWork() {
        // Arrange
        FakeChatClient client = new FakeChatClient();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        DemandSubscriber subscriber = new DemandSubscriber(-1);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello", RunOptions.empty(), cancellation).subscribe(subscriber);
            assertThatThrownBy(() -> subscriber.terminal().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }

        // Assert
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(subscriber.errors()).hasSize(1);
        assertThat(subscriber.completions()).hasValue(0);
        assertThat(client.requests()).isEmpty();
    }

    @Test
    void streamSubscriptionCancel_shouldCancelProviderWithoutTerminalSignal() throws Exception {
        // Arrange
        AtomicBoolean providerCancelled = new AtomicBoolean();
        CountDownLatch providerSubscribed = new CountDownLatch(1);
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming((request, cancellation) ->
                        FakeChatClient.pendingPublisher(providerCancelled, providerSubscribed));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        DemandSubscriber subscriber = new DemandSubscriber(1);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello", RunOptions.empty(), cancellation).subscribe(subscriber);
            assertThat(providerSubscribed.await(5, TimeUnit.SECONDS)).isTrue();
            subscriber.cancel();
            assertEventually(providerCancelled::get);
        }

        // Assert
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(subscriber.updates()).isEmpty();
        assertThat(subscriber.errors()).isEmpty();
        assertThat(subscriber.completions()).hasValue(0);
    }

    @Test
    void publisher_shouldRejectSecondSubscriber_andNewInvocationShouldCreateIndependentRun() {
        // Arrange
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming(List.of(update(0, "one", FinishReason.STOP)))
                .enqueueStreaming(List.of(update(0, "two", FinishReason.STOP)));
        DemandSubscriber first = new DemandSubscriber(Long.MAX_VALUE);
        DemandSubscriber rejected = new DemandSubscriber(Long.MAX_VALUE);
        DemandSubscriber independent = new DemandSubscriber(Long.MAX_VALUE);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            Flow.Publisher<AgentResponseUpdate> publisher = agent.runStreaming("first");
            publisher.subscribe(first);
            first.terminal().join();
            publisher.subscribe(rejected);
            assertThatThrownBy(() -> rejected.terminal().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);

            agent.runStreaming("second").subscribe(independent);
            independent.terminal().join();
        }

        // Assert
        assertThat(first.updates()).extracting(AgentResponseUpdate::text).containsExactly("one");
        assertThat(rejected.errors()).singleElement().isInstanceOf(IllegalStateException.class);
        assertThat(independent.updates()).extracting(AgentResponseUpdate::text).containsExactly("two");
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(0).runContext().runId())
                .isNotEqualTo(client.requests().get(1).runContext().runId());
    }

    @Test
    void subscriberException_shouldCancelActiveProviderAndSuppressFurtherSignals() {
        // Arrange
        AtomicBoolean providerCancelled = new AtomicBoolean();
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming((request, cancellation) ->
                        FakeChatClient.emitThenHold(update(0, "one", null), providerCancelled));
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicInteger terminals = new AtomicInteger();

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello").subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(1);
                }

                @Override
                public void onNext(AgentResponseUpdate item) {
                    nextCalls.incrementAndGet();
                    throw new IllegalStateException("subscriber failed");
                }

                @Override
                public void onError(Throwable throwable) {
                    terminals.incrementAndGet();
                }

                @Override
                public void onComplete() {
                    terminals.incrementAndGet();
                }
            });
            assertEventually(providerCancelled::get);
        }

        // Assert
        assertThat(nextCalls).hasValue(1);
        assertThat(terminals).hasValue(0);
    }

    @Test
    void cancellationCompletionRace_shouldProduceExactlyOneTerminalSignal() {
        // Arrange
        FakeChatClient client = new FakeChatClient();
        client.fallbackStreaming(
                (request, cancellation) -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean requested = new AtomicBoolean();

                    private final AtomicBoolean cancelled = new AtomicBoolean();

                    @Override
                    public void request(long count) {
                        if (!requested.compareAndSet(false, true)) {
                            return;
                        }
                        CountDownLatch start = new CountDownLatch(1);
                        Thread completion = Thread.ofVirtual().start(() -> {
                            await(start);
                            if (!cancelled.get()) {
                                subscriber.onNext(update(0, "raced", FinishReason.STOP));
                                subscriber.onComplete();
                            }
                        });
                        Thread cancellationThread = Thread.ofVirtual().start(() -> {
                            await(start);
                            cancellation.cancel();
                        });
                        start.countDown();
                        join(completion);
                        join(cancellationThread);
                    }

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                    }
                }));

        // Act and assert
        try (ChatAgent agent = new ChatAgent(client)) {
            for (int iteration = 0; iteration < 25; iteration++) {
                DemandSubscriber subscriber = new DemandSubscriber(Long.MAX_VALUE);
                agent.runStreaming("race-" + iteration).subscribe(subscriber);
                subscriber
                        .terminal()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .handle((ignored, failure) -> null)
                        .join();
                assertThat(subscriber.completions().get() + subscriber.errors().size())
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void streamBufferOverflow_shouldFailAndCancelInsteadOfGrowingWithoutBound() {
        // Arrange
        List<ChatResponseUpdate> updates = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> update(index, "chunk-" + index, index == 256 ? FinishReason.STOP : null))
                .toList();
        FakeChatClient client = new FakeChatClient().enqueueStreaming(updates);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        DemandSubscriber subscriber = new DemandSubscriber(0);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("overflow", RunOptions.empty(), cancellation).subscribe(subscriber);
            assertThatThrownBy(() ->
                            subscriber.terminal().orTimeout(5, TimeUnit.SECONDS).join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("maxBufferedUpdates=256");
        }

        // Assert
        assertThat(cancellation.isCancellationRequested()).isTrue();
        assertThat(subscriber.updates()).isEmpty();
        assertThat(subscriber.errors()).hasSize(1);
        assertThat(subscriber.completions()).hasValue(0);
    }

    private static ChatResponseUpdate update(long sequence, String text, FinishReason finishReason) {
        ChatResponseUpdate.Builder builder = ChatResponseUpdate.builder()
                .sequence(sequence)
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(text)));
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        return builder.build();
    }

    private static void assertEventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        assertThat(thread.isAlive()).isFalse();
    }

    private static final class DemandSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
        private final long initialDemand;

        private final CopyOnWriteArrayList<AgentResponseUpdate> updates = new CopyOnWriteArrayList<>();

        private final CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        private final AtomicInteger completions = new AtomicInteger();

        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        private volatile Flow.Subscription subscription;

        private DemandSubscriber(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (initialDemand < 0) {
                subscription.request(0);
            } else if (initialDemand != 0) {
                subscription.request(initialDemand);
            }
        }

        @Override
        public void onNext(AgentResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
            terminal.complete(null);
        }

        private void request(long count) {
            subscription.request(count);
        }

        private void cancel() {
            subscription.cancel();
        }

        private List<AgentResponseUpdate> updates() {
            return List.copyOf(updates);
        }

        private List<Throwable> errors() {
            return List.copyOf(errors);
        }

        private AtomicInteger completions() {
            return completions;
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }
    }
}
