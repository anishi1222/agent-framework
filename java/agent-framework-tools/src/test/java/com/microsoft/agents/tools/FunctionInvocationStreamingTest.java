// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ObservableRunCancellation;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FunctionInvocationStreamingTest {
    @Test
    void streamingAndFiniteRuns_shouldHaveEquivalentLogicalHistoryAndOneInvocationEach() {
        // Arrange
        AtomicInteger finiteInvocations = new AtomicInteger();
        AtomicInteger streamingInvocations = new AtomicInteger();
        FunctionTool finiteTool = doublingTool(finiteInvocations);
        FunctionTool streamingTool = doublingTool(streamingInvocations);
        FunctionCallContent call = new FunctionCallContent(
                "call-stream", "double", StateValue.object(Map.of("value", StateValue.integer(7))));
        ScriptedToolTurnSource finiteSource = new ScriptedToolTurnSource()
                .enqueue(response(new Message(Role.ASSISTANT, List.of(call))))
                .enqueue(response(Message.text(Role.ASSISTANT, "14")));
        ScriptedToolTurnSource streamingSource = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(call))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build()))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent("14")))
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        FunctionLoopResult finite;
        FunctionLoopResult streaming;
        List<ChatResponseUpdate> updates;
        try (FunctionInvocationLoop finiteLoop = new FunctionInvocationLoop(finiteSource, List.of(finiteTool));
                FunctionInvocationLoop streamingLoop =
                        new FunctionInvocationLoop(streamingSource, List.of(streamingTool))) {
            finite = finiteLoop.run(
                    new FunctionInvocationRequest("finite-run", List.of(Message.text(Role.USER, "double"))));
            FunctionInvocationRun run = streamingLoop.startStreaming(
                    new FunctionInvocationRequest("streaming-run", List.of(Message.text(Role.USER, "double"))));
            CompletableFuture<List<ChatResponseUpdate>> collected = collect(run.updates());
            streaming = run.resultAsync().toCompletableFuture().join();
            updates = collected.join();
            assertThat(run.result()).isSameAs(streaming);
        }

        // Assert
        assertThat(finiteInvocations).hasValue(1);
        assertThat(streamingInvocations).hasValue(1);
        assertThat(functionResults(finite))
                .extracting(FunctionResultContent::result)
                .containsExactly(StateValue.integer(14));
        assertThat(functionResults(streaming))
                .extracting(FunctionResultContent::result)
                .containsExactly(StateValue.integer(14));
        assertThat(finite.assistantText()).endsWith("14");
        assertThat(streaming.assistantText()).endsWith("14");
        assertThat(updates.stream()
                        .flatMap(update -> update.contents().stream())
                        .filter(FunctionResultContent.class::isInstance))
                .hasSize(1);
    }

    @Test
    void sharedRunViews_shouldStartOneExecutionAndRejectSecondSubscriber() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = doublingTool(invocations);
        FunctionCallContent call = new FunctionCallContent(
                "call-shared", "double", StateValue.object(Map.of("value", StateValue.integer(3))));
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(call))
                        .build()))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent("6")))
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run = loop.startStreaming(
                    new FunctionInvocationRequest("shared-run", List.of(Message.text(Role.USER, "double"))));
            CompletableFuture<FunctionLoopResult> async = run.resultAsync().toCompletableFuture();
            CompletableFuture<List<ChatResponseUpdate>> firstSubscriber = collect(run.updates());
            CompletableFuture<Throwable> secondSubscriberFailure = collectFailure(run.updates());
            FunctionLoopResult synchronous = run.result();

            // Assert
            assertThat(async.join()).isSameAs(synchronous);
            assertThat(firstSubscriber.join()).isNotEmpty();
            assertThat(secondSubscriberFailure.join())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("one subscriber");
            assertThat(invocations).hasValue(1);
            assertThat(source.requests()).hasSize(2);
        }
    }

    @Test
    void cancellation_shouldTerminateIgnoredProviderStageAndSuppressFurtherWork() {
        // Arrange
        CompletableFuture<ChatResponse> ignoredProvider = new CompletableFuture<>();
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(ignoredProvider);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        FunctionInvocationRequest request = new FunctionInvocationRequest(
                "cancel-provider",
                List.of(Message.text(Role.USER, "wait")),
                FunctionInvocationOptions.defaults(),
                cancellation,
                Map.of());

        // Act / Assert
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.start(request);
            CompletableFuture<FunctionLoopResult> result = run.resultAsync().toCompletableFuture();
            assertThat(run.cancel()).isTrue();
            assertThat(run.cancel()).isFalse();
            assertThatThrownBy(result::join)
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(RunCancelledException.class);
            ignoredProvider.complete(response(Message.text(Role.ASSISTANT, "late")));
            assertThat(source.requests()).hasSize(1);
        }
    }

    @Test
    void cancellationBeforeExecutionStarts_shouldReleaseRunScopedRegistration() {
        // Arrange
        TrackingCancellation cancellation = new TrackingCancellation();
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(emptyResponse());

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.start(new FunctionInvocationRequest(
                    "cancel-before-start",
                    List.of(Message.text(Role.USER, "cancel")),
                    FunctionInvocationOptions.defaults(),
                    cancellation,
                    Map.of()));
            boolean cancelled = run.cancel();

            // Assert
            assertThat(cancelled).isTrue();
            assertThatThrownBy(() -> run.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(RunCancelledException.class);
            assertThat(cancellation.totalRegistrations()).isEqualTo(1);
            assertThat(cancellation.activeRegistrations()).isZero();
            assertThat(source.requests()).isEmpty();
        }
    }

    @Test
    void cancellation_shouldTerminateInFlightToolAndNeverRequestAnotherTurn() throws Exception {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<StateValue> ignoredTool = new CompletableFuture<>();
        FunctionTool tool = FunctionTool.create(metadata(), (context, arguments) -> {
            invocations.incrementAndGet();
            return ignoredTool;
        });
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(
                        Role.ASSISTANT,
                        List.of(new FunctionCallContent(
                                "call-cancel", "double", StateValue.object(Map.of("value", StateValue.integer(4))))))));

        // Act / Assert
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionInvocationRun run = loop.start(
                    new FunctionInvocationRequest("cancel-tool", List.of(Message.text(Role.USER, "double"))));
            CompletableFuture<FunctionLoopResult> result = run.resultAsync().toCompletableFuture();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (invocations.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(invocations).hasValue(1);
            assertThat(run.cancel()).isTrue();
            assertThatThrownBy(result::join)
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(RunCancelledException.class);
            ignoredTool.complete(StateValue.integer(8));
            assertThat(source.requests()).hasSize(1);
        }
    }

    @Test
    void subscriptionCancellation_shouldPropagateToRunCancellation() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent("unused")))
                        .build()));
        FunctionInvocationRun run;

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            run = loop.startStreaming(
                    new FunctionInvocationRequest("cancel-stream", List.of(Message.text(Role.USER, "cancel"))));
            run.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.cancel();
                }

                @Override
                public void onNext(ChatResponseUpdate item) {}

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });

            // Assert
            assertThat(run.cancellation().isCancellationRequested()).isTrue();
            assertThatThrownBy(() -> run.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(RunCancelledException.class);
        }
    }

    @Test
    void updatePublisher_shouldEmitOnlyForPositiveDemandAndSignalInvalidDemandOnce() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(
                        ChatResponseUpdate.builder()
                                .role(Role.ASSISTANT)
                                .contents(List.of(new TextContent("one")))
                                .build(),
                        ChatResponseUpdate.builder()
                                .role(Role.ASSISTANT)
                                .contents(List.of(new TextContent("two")))
                                .finishReason(FinishReason.STOP)
                                .build()));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.startStreaming(
                    new FunctionInvocationRequest("demand-run", List.of(Message.text(Role.USER, "stream"))));
            List<ChatResponseUpdate> updates = new ArrayList<>();
            CompletableFuture<Void> completed = new CompletableFuture<>();
            CompletableFuture<Flow.Subscription> subscriptionFuture = new CompletableFuture<>();
            run.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionFuture.complete(subscription);
                }

                @Override
                public void onNext(ChatResponseUpdate item) {
                    updates.add(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    completed.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    completed.complete(null);
                }
            });
            Flow.Subscription subscription = subscriptionFuture.join();
            subscription.request(1);
            assertThat(updates).hasSize(1);
            assertThat(completed).isNotDone();
            subscription.request(1);
            completed.join();

            // Assert
            assertThat(updates).extracting(ChatResponseUpdate::text).containsExactly("one", "two");
            assertThat(run.resultAsync().toCompletableFuture().join().outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        }

        ScriptedToolTurnSource invalidSource = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent("unused")))
                        .build()));
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(invalidSource, List.of())) {
            FunctionInvocationRun invalidRun = loop.startStreaming(
                    new FunctionInvocationRequest("invalid-demand", List.of(Message.text(Role.USER, "stream"))));
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            invalidRun.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(0);
                }

                @Override
                public void onNext(ChatResponseUpdate item) {}

                @Override
                public void onError(Throwable throwable) {
                    failure.complete(throwable);
                }

                @Override
                public void onComplete() {}
            });
            assertThat(failure.join()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void updatePublisher_shouldRetainOnlyConfiguredFiniteUpdatesUntilDemandArrives() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(List.of(update("one", null), update("two", FinishReason.STOP)));
        FunctionInvocationOptions options = new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 2);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.startStreaming(new FunctionInvocationRequest(
                    "bounded-no-demand", List.of(Message.text(Role.USER, "stream")), options, cancellation, Map.of()));
            List<ChatResponseUpdate> updates = new ArrayList<>();
            CompletableFuture<Void> terminal = new CompletableFuture<>();
            CompletableFuture<Flow.Subscription> subscriptionFuture = new CompletableFuture<>();
            run.updates().subscribe(new CollectingSubscriber(updates, terminal, subscriptionFuture, 0));
            Flow.Subscription subscription = subscriptionFuture.join();

            // Assert
            assertThat(run.resultAsync().toCompletableFuture().join().outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
            assertThat(updates).isEmpty();
            assertThat(terminal).isNotDone();
            subscription.request(2);
            terminal.join();
            assertThat(updates).extracting(ChatResponseUpdate::text).containsExactly("one", "two");
            assertThat(cancellation.isCancellationRequested()).isFalse();
        }
    }

    @Test
    void updatePublisher_shouldFailAndCancelRunWhenConfiguredBufferOverflowsWithoutDemand() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(
                        List.of(update("one", null), update("two", null), update("three", FinishReason.STOP)));
        FunctionInvocationOptions options = new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 2);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.startStreaming(new FunctionInvocationRequest(
                    "bounded-overflow", List.of(Message.text(Role.USER, "stream")), options, cancellation, Map.of()));
            CompletableFuture<Throwable> subscriberFailure = new CompletableFuture<>();
            run.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {}

                @Override
                public void onNext(ChatResponseUpdate item) {
                    subscriberFailure.completeExceptionally(
                            new AssertionError("No update should be delivered without demand."));
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriberFailure.complete(throwable);
                }

                @Override
                public void onComplete() {
                    subscriberFailure.completeExceptionally(
                            new AssertionError("Overflow must not complete successfully."));
                }
            });

            // Assert
            assertThat(subscriberFailure.join())
                    .isInstanceOf(StreamingBufferOverflowException.class)
                    .hasMessageContaining("maxBufferedUpdates=2");
            assertThatThrownBy(() -> run.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(StreamingBufferOverflowException.class);
            assertThat(cancellation.isCancellationRequested()).isTrue();
        }
    }

    @Test
    void subscriptionCancellationAfterRunSuccess_shouldDiscardBufferedUpdatesWithoutCancellingRun() {
        // Arrange
        ScriptedToolTurnSource source =
                new ScriptedToolTurnSource().enqueueStreaming(List.of(update("done", FinishReason.STOP)));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.startStreaming(new FunctionInvocationRequest(
                    "cancel-buffer-after-success",
                    List.of(Message.text(Role.USER, "stream")),
                    new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 1),
                    cancellation,
                    Map.of()));
            CompletableFuture<Flow.Subscription> subscriptionFuture = new CompletableFuture<>();
            run.updates().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionFuture.complete(subscription);
                }

                @Override
                public void onNext(ChatResponseUpdate item) {}

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
            });
            assertThat(run.resultAsync().toCompletableFuture().join().outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
            subscriptionFuture.join().cancel();

            // Assert
            assertThat(cancellation.isCancellationRequested()).isFalse();
        }
    }

    @Test
    void updatePublisher_shouldSupportSlowOneAtATimeDemandAtBufferLimit() {
        // Arrange
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueueStreaming(
                        List.of(update("one", null), update("two", null), update("three", FinishReason.STOP)));
        FunctionInvocationOptions options = new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 1);

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of())) {
            FunctionInvocationRun run = loop.startStreaming(new FunctionInvocationRequest(
                    "bounded-slow-demand",
                    List.of(Message.text(Role.USER, "stream")),
                    options,
                    new DefaultRunCancellation(),
                    Map.of()));
            List<ChatResponseUpdate> updates = collect(run.updates()).join();

            // Assert
            assertThat(updates).extracting(ChatResponseUpdate::text).containsExactly("one", "two", "three");
            assertThat(run.resultAsync().toCompletableFuture().join().outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        }
    }

    @Test
    void completedTurnsAndToolInvocations_shouldReleaseCancellationRegistrations() {
        // Arrange
        int invocationCount = 40;
        TrackingCancellation cancellation = new TrackingCancellation();
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = doublingTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource();
        for (int index = 0; index < invocationCount; index++) {
            source.enqueue(response(new Message(
                    Role.ASSISTANT,
                    List.of(new FunctionCallContent(
                            "call-" + index,
                            "double",
                            StateValue.object(Map.of("value", StateValue.integer(index))))))));
        }
        source.enqueue(ChatResponse.builder().messages(List.of()).build());

        // Act
        FunctionLoopResult result;
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            result = loop.run(new FunctionInvocationRequest(
                    "registration-cleanup",
                    List.of(Message.text(Role.USER, "many")),
                    new FunctionInvocationOptions(64, null, ToolMode.AUTO, false),
                    cancellation,
                    Map.of()));
        }

        // Assert
        assertThat(result.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        assertThat(invocations).hasValue(invocationCount);
        assertThat(cancellation.totalRegistrations()).isEqualTo(1);
        assertThat(cancellation.activeRegistrations()).isZero();
    }

    @Test
    void invocationOptions_shouldRequirePositiveFiniteBufferAndPreserveLegacyConstructorDefault() {
        // Act / Assert
        assertThatThrownBy(() -> new FunctionInvocationOptions(4, null, ToolMode.AUTO, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBufferedUpdates");
        assertThat(new FunctionInvocationOptions(4, null, ToolMode.AUTO, false).maxBufferedUpdates())
                .isEqualTo(FunctionInvocationOptions.DEFAULT_MAX_BUFFERED_UPDATES);
    }

    private static FunctionTool doublingTool(AtomicInteger invocations) {
        return FunctionTool.create(metadata(), (context, arguments) -> {
            invocations.incrementAndGet();
            long value = ((StateValue.NumberValue) arguments.values().get("value"))
                    .value()
                    .longValueExact();
            return CompletableFuture.completedFuture(StateValue.integer(value * 2));
        });
    }

    private static ChatResponseUpdate update(String text, FinishReason finishReason) {
        var builder = ChatResponseUpdate.builder().role(Role.ASSISTANT).contents(List.of(new TextContent(text)));
        if (finishReason != null) {
            builder.finishReason(finishReason);
        }
        return builder.build();
    }

    private static ToolMetadata metadata() {
        return new ToolMetadata(
                "double",
                "Doubles an integer.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of(
                        "type",
                        StateValue.string("object"),
                        "properties",
                        StateValue.object(
                                Map.of("value", StateValue.object(Map.of("type", StateValue.string("integer"))))),
                        "required",
                        StateValue.array(List.of(StateValue.string("value"))),
                        "additionalProperties",
                        StateValue.bool(false))),
                StateValue.object(Map.of("type", StateValue.string("integer"))));
    }

    private static List<FunctionResultContent> functionResults(FunctionLoopResult result) {
        return result.history().stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionResultContent.class::isInstance)
                .map(FunctionResultContent.class::cast)
                .toList();
    }

    private static CompletableFuture<List<ChatResponseUpdate>> collect(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<List<ChatResponseUpdate>> result = new CompletableFuture<>();
        List<ChatResponseUpdate> updates = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                updates.add(item);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(updates));
            }
        });
        return result;
    }

    private static CompletableFuture<Throwable> collectFailure(Flow.Publisher<ChatResponseUpdate> publisher) {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {}

            @Override
            public void onError(Throwable throwable) {
                failure.complete(throwable);
            }

            @Override
            public void onComplete() {
                failure.completeExceptionally(new AssertionError("Expected subscriber failure."));
            }
        });
        return failure;
    }

    private static ChatResponse response(Message message) {
        return ChatResponse.builder().messages(List.of(message)).build();
    }

    private static ChatResponse emptyResponse() {
        return ChatResponse.builder().messages(List.of()).build();
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<ChatResponseUpdate> {
        private final List<ChatResponseUpdate> updates;

        private final CompletableFuture<Void> terminal;

        private final CompletableFuture<Flow.Subscription> subscriptionFuture;

        private final long initialDemand;

        private CollectingSubscriber(
                List<ChatResponseUpdate> updates,
                CompletableFuture<Void> terminal,
                CompletableFuture<Flow.Subscription> subscriptionFuture,
                long initialDemand) {
            this.updates = updates;
            this.terminal = terminal;
            this.subscriptionFuture = subscriptionFuture;
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscriptionFuture.complete(subscription);
            if (initialDemand > 0) {
                subscription.request(initialDemand);
            }
        }

        @Override
        public void onNext(ChatResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminal.complete(null);
        }
    }

    private static final class TrackingCancellation implements ObservableRunCancellation {
        private final DefaultRunCancellation delegate = new DefaultRunCancellation();

        private final AtomicInteger activeRegistrations = new AtomicInteger();

        private final AtomicInteger totalRegistrations = new AtomicInteger();

        @Override
        public boolean cancel() {
            return delegate.cancel();
        }

        @Override
        public boolean isCancellationRequested() {
            return delegate.isCancellationRequested();
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> cancelledAsync() {
            return delegate.cancelledAsync();
        }

        @Override
        public RunCancellationRegistration register(Runnable listener) {
            totalRegistrations.incrementAndGet();
            activeRegistrations.incrementAndGet();
            RunCancellationRegistration registration = delegate.register(listener);
            AtomicBoolean active = new AtomicBoolean(true);
            return () -> {
                if (active.compareAndSet(true, false)) {
                    registration.close();
                    activeRegistrations.decrementAndGet();
                }
            };
        }

        int activeRegistrations() {
            return activeRegistrations.get();
        }

        int totalRegistrations() {
            return totalRegistrations.get();
        }
    }
}
