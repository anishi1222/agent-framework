// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunResult;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.InMemoryHistoryProvider;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoopAgentTest {
    @Test
    void loopAgent_shouldAggregateIterationsAndUseFirstContinuingEvaluator() {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = new ChatAgent(
                client,
                new AgentMetadata("loop-inner", "Loop inner", null),
                ChatOptions.empty(),
                List.of(),
                List.of(new InMemoryHistoryProvider()),
                List.of(),
                List.of(),
                List.of(),
                null);
        AtomicInteger secondEvaluatorCalls = new AtomicInteger();
        LoopEvaluator continueUntilThree = (context, cancellation) -> CompletableFuture.completedFuture(
                context.iteration() < 3
                        ? LoopEvaluation.continueWithFeedback("Continue iteration " + (context.iteration() + 1))
                        : LoopEvaluation.stop());
        LoopEvaluator shouldNotOverride = (context, cancellation) -> {
            secondEvaluatorCalls.incrementAndGet();
            return CompletableFuture.completedFuture(LoopEvaluation.stop());
        };

        try (LoopAgent agent = new LoopAgent(
                inner, List.of(continueUntilThree, shouldNotOverride), LoopAgentOptions.defaults(), true)) {
            AgentResponse<Void> response = agent.run("start");

            assertThat(client.calls()).isEqualTo(3);
            assertThat(secondEvaluatorCalls).hasValue(1);
            assertThat(response.messages())
                    .extracting(Message::text)
                    .containsExactly(
                            "iteration-1",
                            "Continue iteration 2",
                            "iteration-2",
                            "Continue iteration 3",
                            "iteration-3");
        }
    }

    @Test
    void loopAgent_shouldEnforceHardCapAndReturnOnlyFinalResponseWhenConfigured() {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        LoopEvaluator alwaysContinue = (context, cancellation) ->
                CompletableFuture.completedFuture(LoopEvaluation.continueWithFeedback("continue"));
        LoopAgentOptions options = LoopAgentOptions.builder()
                .maxIterations(2)
                .returnFinalOnly(true)
                .build();

        try (LoopAgent agent = new LoopAgent(inner, List.of(alwaysContinue), options, true)) {
            AgentResponse<Void> response = agent.run("start");

            assertThat(client.calls()).isEqualTo(2);
            assertThat(response.messages()).extracting(Message::text).containsExactly("iteration-2");
        }
    }

    @Test
    void loopAgent_shouldExposeSessionAwareRunsAndStreamingTerminalUpdates() {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        LoopEvaluator stop = (context, cancellation) -> CompletableFuture.completedFuture(LoopEvaluation.stop());

        try (LoopAgent agent = new LoopAgent(inner, List.of(stop), LoopAgentOptions.defaults(), true)) {
            AgentSession session = new AgentSession("loop-session");
            assertThat(agent.runAsync(
                                    session,
                                    List.of(Message.text(Role.USER, "start")),
                                    RunOptions.empty(),
                                    new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join()
                            .response())
                    .isPresent();

            List<AgentResponseUpdate> updates = collect(agent.runStreaming(
                    new AgentSession("stream-session"),
                    List.of(Message.text(Role.USER, "stream")),
                    RunOptions.empty(),
                    new DefaultRunCancellation()));
            assertThat(updates).singleElement().satisfies(update -> {
                assertThat(update.text()).isEqualTo("iteration-2");
                assertThat(update.finishReason()).isEqualTo(FinishReason.STOP);
            });
        }
    }

    @Test
    void loopAgent_shouldStreamEveryIterationAndSynthesizedNudgeInOrder() {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        LoopEvaluator continueUntilThree = (context, cancellation) -> CompletableFuture.completedFuture(
                context.iteration() < 3
                        ? LoopEvaluation.continueWithFeedback("Continue iteration " + (context.iteration() + 1))
                        : LoopEvaluation.stop());
        LoopAgentOptions options =
                LoopAgentOptions.builder().returnFinalOnly(true).build();
        CopyOnWriteArrayList<Integer> callsObservedAtDelivery = new CopyOnWriteArrayList<>();

        try (LoopAgent agent = new LoopAgent(inner, List.of(continueUntilThree), options, true)) {
            List<AgentResponseUpdate> updates =
                    collect(agent.runStreaming("start"), update -> callsObservedAtDelivery.add(client.calls()));

            assertThat(updates)
                    .extracting(AgentResponseUpdate::text)
                    .containsExactly(
                            "iteration-1",
                            "Continue iteration 2",
                            "iteration-2",
                            "Continue iteration 3",
                            "iteration-3");
            assertThat(updates)
                    .extracting(AgentResponseUpdate::role)
                    .containsExactly(Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT);
            assertThat(callsObservedAtDelivery).containsExactly(1, 1, 2, 2, 3);
        }
    }

    @Test
    void loopAgent_shouldEvaluateCompleteStreamingToolLoopResponse() {
        AtomicInteger toolInvocations = new AtomicInteger();
        FunctionTool tool = functionTool(toolInvocations);
        ToolLoopChatClient client = new ToolLoopChatClient();
        ChatAgent inner = new ChatAgent(
                client,
                new AgentMetadata("loop-inner", "Loop inner", null),
                ChatOptions.empty(),
                List.of(tool),
                List.of(new InMemoryHistoryProvider()),
                List.of(),
                List.of(),
                List.of(),
                null);
        AtomicReference<AgentResponse<Void>> evaluated = new AtomicReference<>();
        LoopEvaluator stop = (context, cancellation) -> {
            evaluated.set(context.lastResponse());
            return CompletableFuture.completedFuture(LoopEvaluation.stop());
        };

        try (LoopAgent agent = new LoopAgent(inner, List.of(stop), LoopAgentOptions.defaults(), true)) {
            List<AgentResponseUpdate> updates = collect(agent.runStreaming("start"));

            assertThat(updates.stream()
                            .flatMap(update -> update.contents().stream())
                            .map(content -> content.kind()))
                    .containsExactly("functionCall", "functionResult", "text");
            assertThat(evaluated.get().messages())
                    .flatExtracting(Message::contents)
                    .anyMatch(FunctionCallContent.class::isInstance)
                    .anyMatch(FunctionResultContent.class::isInstance)
                    .anyMatch(TextContent.class::isInstance);
            assertThat(evaluated.get().text()).isEqualTo("done");
            assertThat(toolInvocations).hasValue(1);
        }
    }

    @Test
    void loopAgent_shouldRetainExclusiveSessionOwnershipWhileEvaluatorIsPending() throws Exception {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        CountDownLatch evaluating = new CountDownLatch(1);
        CompletableFuture<LoopEvaluation> evaluation = new CompletableFuture<>();
        LoopEvaluator delayed = (context, cancellation) -> {
            evaluating.countDown();
            return evaluation;
        };
        AgentSession session = new AgentSession("exclusive-loop-session");

        try (LoopAgent agent = new LoopAgent(inner, List.of(delayed), LoopAgentOptions.defaults(), true)) {
            CompletionStage<AgentRunResult<Void>> first = agent.runAsync(
                    session,
                    List.of(Message.text(Role.USER, "first")),
                    RunOptions.empty(),
                    new DefaultRunCancellation());
            assertThat(evaluating.await(5, TimeUnit.SECONDS)).isTrue();

            CompletionStage<AgentRunResult<Void>> concurrent = agent.runAsync(
                    session,
                    List.of(Message.text(Role.USER, "second")),
                    RunOptions.empty(),
                    new DefaultRunCancellation());
            assertThatThrownBy(() -> concurrent.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(com.microsoft.agents.agents.SessionBusyException.class);

            evaluation.complete(LoopEvaluation.stop());
            assertThat(first.toCompletableFuture().join().response()).isPresent();
        }
    }

    @Test
    void cancelledStreamingEvaluator_shouldNotMutateSessionAfterLeaseRelease() throws Exception {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        CountDownLatch evaluating = new CountDownLatch(1);
        CompletableFuture<LoopEvaluation> evaluation = new CompletableFuture<>();
        LoopEvaluator delayed = (context, cancellation) -> {
            evaluating.countDown();
            return evaluation;
        };
        LoopAgentOptions options =
                LoopAgentOptions.builder().freshContextPerIteration(true).build();
        AgentSession session = new AgentSession("cancelled-stream-evaluator");
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        try (LoopAgent agent = new LoopAgent(inner, List.of(delayed), options, true)) {
            agent.runStreaming(
                            session,
                            List.of(Message.text(Role.USER, "first")),
                            RunOptions.empty(),
                            new DefaultRunCancellation())
                    .subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription next) {
                            subscription.set(next);
                            next.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(AgentResponseUpdate item) {}

                        @Override
                        public void onError(Throwable throwable) {}

                        @Override
                        public void onComplete() {}
                    });
            assertThat(evaluating.await(5, TimeUnit.SECONDS)).isTrue();

            subscription.get().cancel();
            try (AgentSession.RunLease lease = acquireEventually(session)) {
                assertThat(lease).isNotNull();
                session.putState("post-cancel", StateValue.string("newer-run-state"));
            }
            evaluation.complete(LoopEvaluation.continueWithFeedback("late continuation"));

            assertThat(session.state().get("post-cancel")).contains(StateValue.string("newer-run-state"));
        }
    }

    @Test
    void streamingCancellation_shouldHoldLeaseUntilProviderCleanupCompletes() throws Exception {
        BlockingCompletionProvider provider = new BlockingCompletionProvider();
        CancellableStreamingChatClient client = new CancellableStreamingChatClient();
        ChatAgent inner = new ChatAgent(
                client,
                new AgentMetadata("loop-cleanup", "Loop cleanup", null),
                ChatOptions.empty(),
                List.of(),
                List.of(provider),
                List.of(),
                List.of(),
                List.of(),
                null);
        AgentSession session = new AgentSession("stream-cleanup-session");
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        try (LoopAgent agent = new LoopAgent(inner, List.of(), LoopAgentOptions.defaults(), true)) {
            agent.runStreaming(
                            session,
                            List.of(Message.text(Role.USER, "first")),
                            RunOptions.empty(),
                            new DefaultRunCancellation())
                    .subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription next) {
                            subscription.set(next);
                            next.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(AgentResponseUpdate item) {}

                        @Override
                        public void onError(Throwable throwable) {}

                        @Override
                        public void onComplete() {}
                    });
            assertThat(client.updateSent.await(5, TimeUnit.SECONDS)).isTrue();

            subscription.get().cancel();
            assertThat(provider.completionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(session::acquireRunLease)
                    .isInstanceOf(com.microsoft.agents.agents.SessionBusyException.class);

            provider.release.complete(null);
            try (AgentSession.RunLease lease = acquireEventually(session)) {
                assertThat(lease).isNotNull();
            }
        }
    }

    @Test
    void freshContext_shouldPreserveProcessLocalBackgroundStateOnly() {
        SequencedChatClient client = new SequencedChatClient();
        ChatAgent inner = chatAgent(client);
        String backgroundKey = BackgroundAgentsProvider.STATE_PREFIX + "test";
        LoopEvaluator continueOnce = (context, cancellation) -> {
            context.session().putState(backgroundKey, StateValue.string("running"));
            context.session().putState("iteration-only", StateValue.string("discard"));
            return CompletableFuture.completedFuture(LoopEvaluation.continueWithFeedback("continue"));
        };
        LoopAgentOptions options = LoopAgentOptions.builder()
                .maxIterations(2)
                .freshContextPerIteration(true)
                .build();
        AgentSession session = new AgentSession("fresh-background-state");

        try (LoopAgent agent = new LoopAgent(inner, List.of(continueOnce), options, true)) {
            assertThat(agent.runAsync(
                                    session,
                                    List.of(Message.text(Role.USER, "first")),
                                    RunOptions.empty(),
                                    new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join()
                            .response())
                    .isPresent();
        }

        assertThat(session.state().get(backgroundKey)).contains(StateValue.string("running"));
        assertThat(session.state().get("iteration-only")).isEmpty();
    }

    @Test
    void sessionlessLoopApproval_shouldResumeThroughSyntheticProcessLocalSession() {
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        ChatAgent inner = new ChatAgent(
                new ApprovalChatClient(),
                new AgentMetadata("loop-approval", "Loop approval", null),
                ChatOptions.empty(),
                List.of(tool),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);

        try (LoopAgent agent = new LoopAgent(inner, List.of(), LoopAgentOptions.defaults(), true)) {
            ApprovalRequiredException required;
            try {
                agent.runAsync("write").toCompletableFuture().join();
                throw new AssertionError("Expected approval suspension.");
            } catch (CompletionException failure) {
                assertThat(RunHandles.unwrap(failure)).isInstanceOf(ApprovalRequiredException.class);
                required = (ApprovalRequiredException) RunHandles.unwrap(failure);
            }
            assertThat(agent.processLocalContinuationCountForDiagnostics()).isEqualTo(1);

            ToolApprovalDecision approval = ToolApprovalDecision.approve(
                    required.continuation().approvalRequests().getFirst());
            AgentRunResult<Void> resumed = agent.resumeAsync(required.continuation(), List.of(approval))
                    .toCompletableFuture()
                    .join();

            assertThat(resumed.response().orElseThrow().text()).isEqualTo("done");
            assertThat(invocations).hasValue(1);
            assertThat(agent.processLocalContinuationCountForDiagnostics()).isZero();
        }
    }

    @Test
    void sessionlessLoopApproval_shouldSupportExplicitDiscard() {
        FunctionTool tool = approvalTool(new AtomicInteger());
        ChatAgent inner = new ChatAgent(
                new ApprovalChatClient(),
                new AgentMetadata("loop-discard", "Loop discard", null),
                ChatOptions.empty(),
                List.of(tool),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);

        try (LoopAgent agent = new LoopAgent(inner, List.of(), LoopAgentOptions.defaults(), true)) {
            ApprovalRequiredException required;
            try {
                agent.runAsync("write").toCompletableFuture().join();
                throw new AssertionError("Expected approval suspension.");
            } catch (CompletionException failure) {
                required = (ApprovalRequiredException) RunHandles.unwrap(failure);
            }

            assertThat(agent.discardContinuation(required.continuation())).isTrue();
            assertThat(agent.processLocalContinuationCountForDiagnostics()).isZero();
            assertThat(agent.discardContinuation(required.continuation())).isFalse();
        }
    }

    private static AgentSession.RunLease acquireEventually(AgentSession session) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                return session.acquireRunLease();
            } catch (com.microsoft.agents.agents.SessionBusyException busy) {
                if (System.nanoTime() >= deadline) {
                    throw busy;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.time.Duration.ofMillis(1).toNanos());
            }
        }
    }

    private static ChatAgent chatAgent(ChatClient client) {
        return new ChatAgent(
                client,
                new AgentMetadata("loop-inner", "Loop inner", null),
                ChatOptions.empty(),
                List.of(),
                List.of(new InMemoryHistoryProvider()),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static List<AgentResponseUpdate> collect(Flow.Publisher<AgentResponseUpdate> publisher) {
        return collect(publisher, update -> {});
    }

    private static List<AgentResponseUpdate> collect(
            Flow.Publisher<AgentResponseUpdate> publisher, java.util.function.Consumer<AgentResponseUpdate> observer) {
        CopyOnWriteArrayList<AgentResponseUpdate> updates = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
                observer.accept(item);
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
        });
        terminal.join();
        return List.copyOf(updates);
    }

    private static FunctionTool functionTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "lookup",
                "Looks up a test value.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("tool-value"));
        });
    }

    private static FunctionTool approvalTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "Writes a test value.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
    }

    private static final class SequencedChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();

        private int calls() {
            return calls.get();
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            int iteration = calls.incrementAndGet();
            return CompletableFuture.completedFuture(response(iteration));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            int iteration = calls.incrementAndGet();
            ChatResponseUpdate update = ChatResponseUpdate.builder()
                    .sequence(0)
                    .contents(List.of(new com.microsoft.agents.core.TextContent("iteration-" + iteration)))
                    .role(Role.ASSISTANT)
                    .responseId("response-" + iteration)
                    .messageId("message-" + iteration)
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean signalled;

                @Override
                public void request(long count) {
                    if (signalled) {
                        return;
                    }
                    signalled = true;
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    signalled = true;
                }
            });
        }

        private static ChatResponse response(int iteration) {
            return ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "iteration-" + iteration)))
                    .responseId("response-" + iteration)
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    private static final class ToolLoopChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new AssertionError("The tool-loop test must use streaming."));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            int call = calls.incrementAndGet();
            ChatResponseUpdate update = call == 1
                    ? ChatResponseUpdate.builder()
                            .sequence(0)
                            .contents(List.of(
                                    new FunctionCallContent("call-loop", "lookup", StateValue.object(Map.of()))))
                            .role(Role.ASSISTANT)
                            .responseId("response-tool")
                            .messageId("message-tool")
                            .finishReason(FinishReason.TOOL_CALLS)
                            .build()
                    : ChatResponseUpdate.builder()
                            .sequence(1)
                            .contents(List.of(new TextContent("done")))
                            .role(Role.ASSISTANT)
                            .responseId("response-final")
                            .messageId("message-final")
                            .finishReason(FinishReason.STOP)
                            .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean signalled;

                @Override
                public void request(long count) {
                    if (signalled) {
                        return;
                    }
                    signalled = true;
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    signalled = true;
                }
            });
        }
    }

    private static final class ApprovalChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(ChatResponse.builder()
                        .messages(List.of(new Message(
                                Role.ASSISTANT,
                                List.of(new FunctionCallContent(
                                        "call-approval", "write", StateValue.object(Map.of()))))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
            }
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class CancellableStreamingChatClient implements ChatClient {
        private final CountDownLatch updateSent = new CountDownLatch(1);

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.failedFuture(new AssertionError("Expected streaming execution."));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            ChatResponseUpdate update = ChatResponseUpdate.builder()
                    .sequence(0)
                    .contents(List.of(new TextContent("working")))
                    .role(Role.ASSISTANT)
                    .responseId("cleanup-response")
                    .messageId("cleanup-message")
                    .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public void request(long count) {
                    if (!sent) {
                        sent = true;
                        subscriber.onNext(update);
                        updateSent.countDown();
                    }
                }

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class BlockingCompletionProvider implements ContextProvider {
        private final CountDownLatch completionStarted = new CountDownLatch(1);

        private final CompletableFuture<Void> release = new CompletableFuture<>();

        @Override
        public String id() {
            return "blocking-completion";
        }

        @Override
        public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
            return CompletableFuture.completedFuture(ContextContribution.empty());
        }

        @Override
        public CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
            completionStarted.countDown();
            return release;
        }
    }
}
