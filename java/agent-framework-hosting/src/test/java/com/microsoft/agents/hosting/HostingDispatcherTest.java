// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ObservableRunCancellation;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostingDispatcherTest {
    @Test
    void dispatcher_shouldBoundAuthorizationAndReleaseRequestContext() {
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new PendingAgent());
        HostingLimits limits =
                HostingLimits.builder().idleTimeout(Duration.ofMillis(50)).build();
        HostingAuthorizer pending = (context, descriptor, action) -> new CompletableFuture<>();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits, pending)) {
            assertThatThrownBy(() -> dispatcher
                            .listAsync(context("owner"), HostingRouteKind.AGENT)
                            .toCompletableFuture()
                            .orTimeout(2, TimeUnit.SECONDS)
                            .join())
                    .hasRootCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.RUN_TIMEOUT);
        }
    }

    @Test
    void dispatcher_shouldCompletePendingAuthorizationOnClose() {
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new PendingAgent());
        HostingAuthorizer pending = (context, descriptor, action) -> new CompletableFuture<>();
        HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults(), pending);
        CompletableFuture<?> authorization =
                dispatcher.listAsync(context("owner"), HostingRouteKind.AGENT).toCompletableFuture();

        dispatcher.close();

        assertThatThrownBy(authorization::join)
                .hasRootCauseInstanceOf(HostingException.class)
                .rootCause()
                .extracting(failure -> ((HostingException) failure).error().code())
                .isEqualTo(HostingErrorCode.CLIENT_CANCELLED);
    }

    @Test
    void dispatcher_shouldAtomicallyCloseAuthorizationAdmissionsAndIgnoreLateAuthorizerCompletion()
            throws InterruptedException {
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new PendingAgent());
        ConcurrentLinkedQueue<CompletableFuture<HostingAuthorizationDecision>> authorizations =
                new ConcurrentLinkedQueue<>();
        HostingAuthorizer pending = (context, descriptor, action) -> {
            CompletableFuture<HostingAuthorizationDecision> authorization = new CompletableFuture<>();
            authorizations.add(authorization);
            return authorization;
        };
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("authorization-close-test")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        HostingDispatcher dispatcher =
                new HostingDispatcher(registry, HostingLimits.defaults(), pending, Clock.systemUTC(), scheduler);
        ArrayList<TrackingCancellation> cancellations = new ArrayList<>();
        ArrayList<CompletableFuture<?>> results = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            TrackingCancellation cancellation = new TrackingCancellation();
            cancellations.add(cancellation);
            results.add(dispatcher
                    .listAsync(context("owner-" + index, cancellation), HostingRouteKind.AGENT)
                    .toCompletableFuture());
        }
        assertThat(dispatcher.pendingAuthorizationCount()).isEqualTo(100);
        assertThat(scheduler.getQueue()).hasSize(100);
        assertThat(cancellations)
                .allSatisfy(
                        cancellation -> assertThat(cancellation.listenerCount()).isEqualTo(1));

        List<Thread> closers = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> Thread.startVirtualThread(dispatcher::close))
                .toList();
        for (Thread closer : closers) {
            closer.join();
        }
        authorizations.forEach(authorization -> authorization.complete(HostingAuthorizationDecision.allow()));

        for (CompletableFuture<?> result : results) {
            assertThatThrownBy(() -> result.orTimeout(2, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.CLIENT_CANCELLED);
        }
        assertThat(dispatcher.pendingAuthorizationCount()).isZero();
        assertThat(scheduler.getQueue()).isEmpty();
        assertThat(scheduler.isShutdown()).isTrue();
        assertThat(cancellations)
                .allSatisfy(
                        cancellation -> assertThat(cancellation.listenerCount()).isZero());
    }

    @Test
    void dispatcher_shouldCompleteFailedOutcomeWhenWorkflowEncodingThrows() {
        HostingRegistry registry = new HostingRegistry();
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("encoding-failure", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("output", FunctionExecutor.sync(String.class, String.class, (input, context) -> input));
        HostingWorkflowCodec<String, String> codec = new HostingWorkflowCodec<>() {
            @Override
            public String decodeInput(HostingRunRequest request) {
                return "input";
            }

            @Override
            public StateValue encodeOutput(String output) {
                throw new HostingException(HostingErrorCode.OVERFLOW, "Encoded output is too large.");
            }
        };
        try (Workflow<String, String> workflow =
                        builder.entry(node).output(node).build();
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            registry.registerWorkflow("encoding-failure", workflow, codec);

            HostingOutcome outcome = dispatcher
                    .runAsync(
                            context("owner"),
                            HostingRouteKind.WORKFLOW,
                            "encoding-failure",
                            HostingRunRequest.forWorkflow(StateValue.string("input")))
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            assertThat(outcome.status()).isEqualTo(HostingOutcomeStatus.OVERFLOW);
            assertThat(outcome.error().code()).isEqualTo(HostingErrorCode.OVERFLOW);
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void dispatcher_shouldRunRealTypedWorkflowFiniteAndStreaming() {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("workflow", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "append", FunctionExecutor.sync(String.class, String.class, (input, context) -> input + "!"));
        try (Workflow<String, String> workflow =
                        builder.entry(node).output(node).build();
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            registry.registerWorkflow("workflow", workflow, HostingWorkflowCodecs.text());
            HostingRunRequest request = HostingRunRequest.forWorkflow(StateValue.string("hello"));

            // Act
            HostingOutcome finite = dispatcher
                    .runAsync(context("owner"), HostingRouteKind.WORKFLOW, "workflow", request)
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            HostingRun streaming = dispatcher
                    .startStreamingAsync(context("owner"), HostingRouteKind.WORKFLOW, "workflow", request)
                    .toCompletableFuture()
                    .join();
            EventSubscriber events = new EventSubscriber();
            streaming.events().subscribe(events);
            List<HostingEvent> streamed =
                    events.terminal.orTimeout(5, TimeUnit.SECONDS).join();
            HostingOutcome streamOutcome =
                    streaming.terminalAsync().toCompletableFuture().join();

            // Assert
            assertThat(finite.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            assertThat(finite.result().toString()).contains("hello!");
            assertThat(streamed).isNotEmpty();
            assertThat(streamed)
                    .extracting(HostingEvent::sequence)
                    .containsExactlyElementsOf(java.util.stream.LongStream.range(0, streamed.size())
                            .boxed()
                            .toList());
            assertThat(streamOutcome.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            assertThat(dispatcher.activeRunCount()).isZero();
        }
    }

    @Test
    void dispatcher_shouldResumeRealChatAgentApprovalOnceAndEnforceIsolation() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        FunctionCallContent call =
                new FunctionCallContent("call-1", "write", StateValue.object(Map.of("value", StateValue.string("x"))));
        QueueChatClient client = new QueueChatClient(
                response(new Message(Role.ASSISTANT, List.of(call)), "approval", FinishReason.TOOL_CALLS),
                response(Message.text(Role.ASSISTANT, "done"), "done", FinishReason.STOP));
        AgentMetadata metadata = new AgentMetadata("approval-agent", "Approval agent", "test");
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = new ChatAgent(client, metadata, ChatOptions.empty(), List.of(tool));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            registry.registerAgent(agent);
            HostingRequestContext owner = context("owner");
            HostingOutcome suspended = dispatcher
                    .runAsync(
                            owner,
                            HostingRouteKind.AGENT,
                            "approval-agent",
                            HostingRunRequest.forAgent(List.of(Message.text(Role.USER, "write")), RunOptions.empty()))
                    .toCompletableFuture()
                    .join();
            HostingContinuationDescriptor continuation = suspended.continuation();
            HostingResumeRequest resume = new HostingResumeRequest(
                    continuation.token(),
                    HostingContinuationType.APPROVAL,
                    List.of(new HostingApprovalDecision(
                            continuation.approvalRequests().getFirst().approvalId(), true, null)),
                    null);

            // Act
            HostingOutcome denied = dispatcher
                    .resumeAsync(context("other"), HostingRouteKind.AGENT, "approval-agent", suspended.runId(), resume)
                    .toCompletableFuture()
                    .join();
            HostingOutcome completed = dispatcher
                    .resumeAsync(owner, HostingRouteKind.AGENT, "approval-agent", suspended.runId(), resume)
                    .toCompletableFuture()
                    .join();
            HostingOutcome replay = dispatcher
                    .resumeAsync(owner, HostingRouteKind.AGENT, "approval-agent", suspended.runId(), resume)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(suspended.status()).isEqualTo(HostingOutcomeStatus.APPROVAL_REQUIRED);
            assertThat(denied.error().code()).isEqualTo(HostingErrorCode.FORBIDDEN);
            assertThat(completed.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            assertThat(replay.error().code()).isEqualTo(HostingErrorCode.CONTINUATION_REPLAYED);
            assertThat(invocations).hasValue(1);
            assertThat(dispatcher.continuationCount()).isZero();
        }
    }

    @Test
    void dispatcher_shouldCompleteWhenContinuationCapacityIsExhausted() {
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        FunctionCallContent firstCall = new FunctionCallContent(
                "call-capacity-1", "write", StateValue.object(Map.of("value", StateValue.string("one"))));
        FunctionCallContent secondCall = new FunctionCallContent(
                "call-capacity-2", "write", StateValue.object(Map.of("value", StateValue.string("two"))));
        QueueChatClient client = new QueueChatClient(
                response(new Message(Role.ASSISTANT, List.of(firstCall)), "approval-1", FinishReason.TOOL_CALLS),
                response(new Message(Role.ASSISTANT, List.of(secondCall)), "approval-2", FinishReason.TOOL_CALLS));
        HostingLimits limits =
                HostingLimits.builder().maxProcessLocalContinuations(1).build();
        HostingRegistry registry = new HostingRegistry();
        try (ChatAgent agent = new ChatAgent(
                        client,
                        new AgentMetadata("capacity-agent", "Capacity agent", "test"),
                        ChatOptions.empty(),
                        List.of(tool));
                HostingDispatcher dispatcher = new HostingDispatcher(registry, limits)) {
            registry.registerAgent(agent);
            HostingRunRequest request =
                    HostingRunRequest.forAgent(List.of(Message.text(Role.USER, "write")), RunOptions.empty());

            HostingOutcome first = dispatcher
                    .runAsync(context("owner"), HostingRouteKind.AGENT, "capacity-agent", request)
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            HostingOutcome second = dispatcher
                    .runAsync(context("owner"), HostingRouteKind.AGENT, "capacity-agent", request)
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            assertThat(first.status()).isEqualTo(HostingOutcomeStatus.APPROVAL_REQUIRED);
            assertThat(second.status()).isEqualTo(HostingOutcomeStatus.FAILED);
            assertThat(second.error().code()).isEqualTo(HostingErrorCode.TOO_MANY_REQUESTS);
            assertThat(dispatcher.activeRunCount()).isZero();
            assertThat(dispatcher.continuationCount()).isEqualTo(1);
        }
    }

    @Test
    void dispatcher_shouldCancelStreamingRunOnceAcrossEndpointRace() {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        PendingAgent agent = new PendingAgent();
        registry.registerAgent(agent);
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            HostingRequestContext context = context("owner");
            HostingRun run = dispatcher
                    .startStreamingAsync(
                            context,
                            HostingRouteKind.AGENT,
                            "pending",
                            HostingRunRequest.forAgent(List.of(Message.text(Role.USER, "wait")), RunOptions.empty()))
                    .toCompletableFuture()
                    .join();
            EventSubscriber subscriber = new EventSubscriber();
            run.events().subscribe(subscriber);

            // Act
            boolean first = dispatcher
                    .cancelAsync(context, HostingRouteKind.AGENT, "pending", run.runId())
                    .toCompletableFuture()
                    .join();
            HostingOutcome outcome = run.terminalAsync()
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            // Assert
            assertThat(first).isTrue();
            assertThat(outcome.status()).isEqualTo(HostingOutcomeStatus.CANCELLED);
            assertThat(agent.cancelled).isTrue();
            assertThat(dispatcher.activeRunCount()).isZero();
            assertThatThrownBy(() -> dispatcher
                            .cancelAsync(context, HostingRouteKind.AGENT, "pending", run.runId())
                            .toCompletableFuture()
                            .join())
                    .hasCauseInstanceOf(HostingException.class);
        }
    }

    @Test
    void dispatcher_shouldAlwaysPublishOneOverflowTerminalBeforePropagatingCancellation() {
        int attempts = 500;
        HostingRegistry registry = new HostingRegistry();
        OverflowingAgent agent = new OverflowingAgent();
        registry.registerAgent(agent);
        HostingLimits limits = HostingLimits.builder().maxSseBufferedEvents(1).build();
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, limits)) {
            HostingRunRequest request =
                    HostingRunRequest.forAgent(List.of(Message.text(Role.USER, "overflow")), RunOptions.empty());

            for (int attempt = 0; attempt < attempts; attempt++) {
                HostingRun run = dispatcher
                        .startStreamingAsync(context("owner"), HostingRouteKind.AGENT, "overflowing", request)
                        .toCompletableFuture()
                        .join();
                TerminalCountingSubscriber subscriber = new TerminalCountingSubscriber();
                run.events().subscribe(subscriber);

                HostingOutcome outcome = run.terminalAsync()
                        .toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();
                subscriber.terminal.orTimeout(5, TimeUnit.SECONDS).join();

                assertThat(outcome.status()).isEqualTo(HostingOutcomeStatus.OVERFLOW);
                assertThat(outcome.error().code()).isEqualTo(HostingErrorCode.OVERFLOW);
                assertThat(subscriber.completions).hasValue(1);
                assertThat(subscriber.failures).hasValue(0);
                assertThat(dispatcher.activeRunCount()).isZero();
            }
        }
        assertThat(agent.cancellations).hasValue(attempts);
    }

    @Test
    void dispatcher_shouldApplyAuthorizerBeforeDiscoveryOrExecution() {
        // Arrange
        HostingRegistry registry = new HostingRegistry();
        registry.registerAgent(new PendingAgent());
        HostingAuthorizer deny = (context, descriptor, action) ->
                CompletableFuture.completedFuture(HostingAuthorizationDecision.deny("test-policy"));
        try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults(), deny)) {

            // Act / Assert
            assertThatThrownBy(() -> dispatcher
                            .listAsync(context("owner"), HostingRouteKind.AGENT)
                            .toCompletableFuture()
                            .join())
                    .hasCauseInstanceOf(HostingException.class)
                    .rootCause()
                    .extracting(failure -> ((HostingException) failure).error().code())
                    .isEqualTo(HostingErrorCode.FORBIDDEN);
        }
    }

    private static FunctionTool approvalTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "Write a value",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
    }

    private static ChatResponse response(Message message, String responseId, FinishReason finishReason) {
        return new ChatResponse(
                List.of(message),
                responseId,
                "conversation",
                "test-model",
                Instant.parse("2026-08-09T00:00:00Z"),
                finishReason,
                null,
                null,
                Map.of(),
                List.of());
    }

    private static HostingRequestContext context(String principal) {
        return context(principal, new com.microsoft.agents.core.DefaultRunCancellation());
    }

    private static HostingRequestContext context(String principal, RunCancellation cancellation) {
        return new HostingRequestContext(
                "request-" + principal,
                "correlation-" + principal,
                new HostingPrincipal(principal, "tenant"),
                Map.of(),
                Map.of(),
                cancellation);
    }

    private static final class QueueChatClient implements ChatClient {
        private final ArrayDeque<ChatResponse> responses;

        private QueueChatClient(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            ChatResponse response = responses.pollFirst();
            return response == null
                    ? CompletableFuture.failedFuture(new AssertionError("No response configured."))
                    : CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onError(new AssertionError("Streaming was not expected."));
                }

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class PendingAgent implements Agent<Void> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("pending", "Pending", "test");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    // This run remains active until cancellation.
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }
    }

    private static final class OverflowingAgent implements Agent<Void> {
        private final AtomicInteger cancellations = new AtomicInteger();

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("overflowing", "Overflowing", "Non-compliant overflow test agent");
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private final AtomicBoolean cancelled = new AtomicBoolean();

                    @Override
                    public void request(long count) {
                        // This deliberately non-compliant source emits without demand below.
                    }

                    @Override
                    public void cancel() {
                        if (cancelled.compareAndSet(false, true)) {
                            cancellations.incrementAndGet();
                            cancellation.cancel();
                        }
                    }
                });
                subscriber.onNext(update(0, "one"));
                subscriber.onNext(update(1, "two"));
            };
        }

        private static AgentResponseUpdate update(long sequence, String text) {
            return AgentResponseUpdate.builder()
                    .sequence(sequence)
                    .role(Role.ASSISTANT)
                    .contents(List.of(new TextContent(text)))
                    .build();
        }
    }

    private static final class TerminalCountingSubscriber implements Flow.Subscriber<HostingEvent> {
        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        private final AtomicInteger completions = new AtomicInteger();

        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            // Withholding demand forces the non-compliant source through the bounded buffer.
        }

        @Override
        public void onNext(HostingEvent item) {
            throw new AssertionError("No buffered event should be delivered after overflow.");
        }

        @Override
        public void onError(Throwable throwable) {
            failures.incrementAndGet();
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
            terminal.complete(null);
        }
    }

    private static final class EventSubscriber implements Flow.Subscriber<HostingEvent> {
        private final ArrayList<HostingEvent> events = new ArrayList<>();

        private final CompletableFuture<List<HostingEvent>> terminal = new CompletableFuture<>();

        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            subscription.request(1);
        }

        @Override
        public void onNext(HostingEvent item) {
            events.add(item);
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminal.complete(List.copyOf(events));
        }
    }

    private static final class TrackingCancellation implements ObservableRunCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private final CompletableFuture<Void> notification = new CompletableFuture<>();

        private final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            notification.complete(null);
            List.copyOf(listeners).forEach(listener -> {
                if (listeners.remove(listener)) {
                    listener.run();
                }
            });
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public CompletionStage<Void> cancelledAsync() {
            return notification.minimalCompletionStage();
        }

        @Override
        public RunCancellationRegistration register(Runnable listener) {
            if (cancelled.get()) {
                listener.run();
                return () -> {};
            }
            listeners.add(listener);
            if (cancelled.get() && listeners.remove(listener)) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private int listenerCount() {
            return listeners.size();
        }
    }
}
