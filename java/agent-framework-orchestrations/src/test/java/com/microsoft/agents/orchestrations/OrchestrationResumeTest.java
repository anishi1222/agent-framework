// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolApprovalRequest;
import com.microsoft.agents.tools.ToolApprovalState;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrchestrationResumeTest {
    @Test
    void sequentialApproval_shouldResumeSameRunWithoutRestartingCompletedParticipants() {
        AtomicInteger toolInvocations = new AtomicInteger();
        ChatAgent approver = approvalAgent("approver", "approved", toolInvocations);
        TestAgent finisher = TestAgent.responding("finisher", "finished");
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(approver), OrchestrationParticipant.of(finisher)))
                .id("resume-sequential")
                .build();

        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run(
                "start",
                OrchestrationRunOptions.builder()
                        .runId("logical-run")
                        .sessionPolicy(OrchestrationSessionPolicy.SHARED)
                        .build());

        assertThat(suspended.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(suspended.continuation().pattern()).isEqualTo(OrchestrationPattern.SEQUENTIAL);
        assertThat(suspended.continuation().restartCapable()).isFalse();
        assertThat(finisher.invocationCount()).isZero();

        OrchestrationResult<AgentResponse<?>> resumed = orchestration
                .resumeAsync(
                        suspended.continuation(),
                        approve(suspended.continuation()),
                        OrchestrationRunOptions.builder().runId("logical-run").build())
                .toCompletableFuture()
                .join();

        assertThat(resumed.runId()).isEqualTo(suspended.runId());
        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(resumed.output().text()).isEqualTo("finished");
        assertThat(resumed.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.COMPLETED, ParticipantStatus.COMPLETED);
        assertThat(finisher.invocationCount()).isOne();
        assertThat(toolInvocations).hasValue(1);
        assertThat(resumed.events().subList(0, suspended.events().size()))
                .containsExactlyElementsOf(suspended.events());
        assertThat(resumed.events())
                .extracting(OrchestrationEvent::sequence)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.range(0, resumed.events().size())
                                .boxed()
                                .toList());
        assertThat(resumed.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.RUN_STARTED)
                .hasSize(1);
        assertThatThrownBy(() -> orchestration.resume(suspended.continuation(), approve(suspended.continuation())))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("already consumed");

        orchestration.close();
        approver.close();
        finisher.close();
    }

    @Test
    void concurrentApproval_shouldRetainCompletedSiblingsAndAggregateAfterResume() {
        ChatAgent approver = approvalAgent("approver", "approved", new AtomicInteger());
        TestAgent completed = TestAgent.responding("completed", "sibling");
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(approver), OrchestrationParticipant.of(completed)))
                .id("resume-concurrent")
                .build();

        OrchestrationResult<List<AgentResponse<?>>> suspended = orchestration.run("start");

        assertThat(suspended.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(completed.invocationCount()).isOne();

        OrchestrationResult<List<AgentResponse<?>>> resumed =
                orchestration.resume(suspended.continuation(), approve(suspended.continuation()));

        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(resumed.output()).extracting(AgentResponse::text).containsExactly("approved", "sibling");
        assertThat(completed.invocationCount()).isOne();
        assertThat(resumed.participantResults())
                .extracting(ParticipantResult::status)
                .containsExactly(ParticipantStatus.COMPLETED, ParticipantStatus.COMPLETED);

        orchestration.close();
        approver.close();
        completed.close();
    }

    @Test
    void concurrentMultipleApprovals_shouldResumePendingParticipantsOneAtATimeInDeclarationOrder() {
        AtomicInteger firstTool = new AtomicInteger();
        AtomicInteger secondTool = new AtomicInteger();
        ChatAgent first = approvalAgent("first", "first-approved", firstTool);
        ChatAgent second = approvalAgent("second", "second-approved", secondTool);
        ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(first), OrchestrationParticipant.of(second)))
                .build();

        OrchestrationResult<List<AgentResponse<?>>> firstSuspension = orchestration.run("start");
        OrchestrationResult<List<AgentResponse<?>>> secondSuspension =
                orchestration.resume(firstSuspension.continuation(), approve(firstSuspension.continuation()));
        OrchestrationResult<List<AgentResponse<?>>> completed =
                orchestration.resume(secondSuspension.continuation(), approve(secondSuspension.continuation()));

        assertThat(firstSuspension.continuation().participantId()).isEqualTo("first");
        assertThat(secondSuspension.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(secondSuspension.continuation().participantId()).isEqualTo("second");
        assertThat(completed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(completed.output())
                .extracting(AgentResponse::text)
                .containsExactly("first-approved", "second-approved");
        assertThat(firstTool).hasValue(1);
        assertThat(secondTool).hasValue(1);
        assertThat(completed.participantResults()).hasSize(2);

        orchestration.close();
        first.close();
        second.close();
    }

    @Test
    void concurrentError_shouldTakePrecedenceOverInputRequiredIndependentOfCompletionTiming() {
        for (long failureDelay : List.of(0L, 75L)) {
            ChatAgent approver = approvalAgent("approver-" + failureDelay, "unused", new AtomicInteger());
            TestAgent failing =
                    new TestAgent("failing-" + failureDelay, failureDelay, (messages, options, invocation) -> {
                        throw new IllegalStateException("real failure");
                    });
            ConcurrentOrchestration<List<AgentResponse<?>>> orchestration = ConcurrentOrchestration.builder(
                            List.of(OrchestrationParticipant.of(approver), OrchestrationParticipant.of(failing)))
                    .failurePolicy(ConcurrentFailurePolicy.COLLECT_ERRORS)
                    .build();

            OrchestrationResult<List<AgentResponse<?>>> result = orchestration.run("start");

            assertThat(result.outcome()).isEqualTo(OrchestrationOutcome.FAILED);
            assertThat(result.continuation()).isNull();
            assertThat(result.errors()).singleElement();
            assertThat(result.participantResults().getFirst().status()).isEqualTo(ParticipantStatus.INPUT_REQUIRED);
            assertThat(result.participantResults().getFirst().agentContinuation())
                    .isEmpty();
            assertThat(orchestration.pendingContinuationCountForDiagnostics()).isZero();

            orchestration.close();
            approver.close();
            failing.close();
        }
    }

    @Test
    void handoffHumanInput_shouldContinueAfterCompletedRequestTurn() {
        TestAgent participant = new TestAgent(
                "human",
                0,
                (messages, options, invocation) -> invocation == 0
                        ? HandoffOrchestrationTest.directiveResponse(
                                "human",
                                "request_human_input",
                                StateValue.object(Map.of("prompt", StateValue.string("Account number?"))))
                        : TestAgent.response("human", "account accepted"));
        HandoffOrchestration orchestration = HandoffOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .id("resume-handoff")
                .build();

        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run("start");
        OrchestrationResult<AgentResponse<?>> resumed =
                orchestration.resume(suspended.continuation(), OrchestrationResumeInput.human("A-123"));

        assertThat(suspended.continuation().kind()).isEqualTo(OrchestrationContinuationKind.HUMAN_INPUT);
        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(resumed.output().text()).isEqualTo("account accepted");
        assertThat(resumed.transcript()).extracting(Message::text).contains("A-123", "account accepted");
        assertThat(participant.invocationCount()).isEqualTo(2);
        assertThat(resumed.participantResults()).hasSize(2);

        orchestration.close();
        participant.close();
    }

    @Test
    void groupChatApproval_shouldResumeSelectedTurnWithoutSelectingItAgain() {
        ChatAgent participant = approvalAgent("speaker", "group-approved", new AtomicInteger());
        AtomicInteger managerCalls = new AtomicInteger();
        GroupChatManager manager = context -> {
            managerCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    context.transcript().stream()
                                    .anyMatch(message -> message.text().contains("group-approved"))
                            ? GroupChatDecision.terminate("done")
                            : GroupChatDecision.select("speaker"));
        };
        GroupChatOrchestration orchestration = GroupChatOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .manager(manager)
                .id("resume-group-chat")
                .build();

        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run("topic");
        int managerCallsAtSuspension = managerCalls.get();
        OrchestrationResult<AgentResponse<?>> resumed =
                orchestration.resume(suspended.continuation(), approve(suspended.continuation()));

        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.TERMINATED);
        assertThat(resumed.output().text()).isEqualTo("group-approved");
        assertThat(resumed.participantResults())
                .singleElement()
                .extracting(ParticipantResult::status)
                .isEqualTo(ParticipantStatus.COMPLETED);
        assertThat(managerCalls).hasValue(managerCallsAtSuspension + 1);
        assertThat(resumed.events())
                .filteredOn(event -> event.type() == OrchestrationEventType.SPEAKER_SELECTED)
                .hasSize(1);

        orchestration.close();
        participant.close();
    }

    @Test
    void magenticPlanReview_shouldResumeApprovedPlanWithoutReplanning() {
        TestAgent worker = TestAgent.responding("worker", "work complete");
        CompletingMagenticManager manager = new CompletingMagenticManager();
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), manager)
                .id("resume-magentic")
                .requirePlanReview(true)
                .build();

        OrchestrationResult<MagenticResult> suspended = orchestration.run("work");
        OrchestrationResult<MagenticResult> resumed =
                orchestration.resume(suspended.continuation(), OrchestrationResumeInput.approvePlan());

        assertThat(suspended.continuation().kind()).isEqualTo(OrchestrationContinuationKind.PLAN_REVIEW);
        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(resumed.output().response().text()).isEqualTo("final");
        assertThat(manager.planCalls).hasValue(1);
        assertThat(manager.replanCalls).hasValue(0);
        assertThat(worker.invocationCount()).isOne();

        orchestration.close();
        worker.close();
    }

    @Test
    void magenticRejectedPlan_shouldReplanAndRequireAOneTimeDecisionForTheRevision() {
        TestAgent worker = TestAgent.responding("worker", "work complete");
        CompletingMagenticManager manager = new CompletingMagenticManager();
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), manager)
                .requirePlanReview(true)
                .build();

        OrchestrationResult<MagenticResult> initial = orchestration.run("work");
        OrchestrationResult<MagenticResult> revised = orchestration.resume(
                initial.continuation(), OrchestrationResumeInput.rejectPlan("Use the safer plan."));

        assertThat(revised.outcome()).isEqualTo(OrchestrationOutcome.INPUT_REQUIRED);
        assertThat(revised.continuation().continuationId())
                .isNotEqualTo(initial.continuation().continuationId());
        assertThat(revised.transcript()).extracting(Message::text).contains("Use the safer plan.");
        assertThat(manager.replanCalls).hasValue(1);
        assertThat(worker.invocationCount()).isZero();

        OrchestrationResult<MagenticResult> completed =
                orchestration.resume(revised.continuation(), OrchestrationResumeInput.approvePlan());
        assertThat(completed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);
        assertThat(worker.invocationCount()).isOne();

        orchestration.close();
        worker.close();
    }

    @Test
    void resumeValidation_shouldRejectWrongInputRunParticipantPatternOrInstanceWithoutConsumingToken() {
        ChatAgent participant = approvalAgent("approver", "approved", new AtomicInteger());
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .id("validation")
                .build();
        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run(
                "start",
                OrchestrationRunOptions.builder().runId("validation-run").build());
        OrchestrationContinuation continuation = suspended.continuation();

        assertThatThrownBy(() -> orchestration.resume(continuation, OrchestrationResumeInput.human("wrong")))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("does not accept");
        assertThatThrownBy(() -> orchestration.resume(
                        continuation,
                        approve(continuation),
                        OrchestrationRunOptions.builder().runId("other-run").build()))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("runId");

        OrchestrationContinuation wrongParticipant = copy(
                continuation, continuation.orchestrationId(), continuation.runId(), continuation.pattern(), "other");
        assertThatThrownBy(() -> orchestration.resume(wrongParticipant, approve(continuation)))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("participant");

        ConcurrentOrchestration<List<AgentResponse<?>>> wrongPattern = ConcurrentOrchestration.builder(
                        List.of(OrchestrationParticipant.of(TestAgent.responding("other", "other"))))
                .id("validation")
                .build();
        assertThatThrownBy(() -> wrongPattern.resume(continuation, approve(continuation)))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("pattern");

        SequentialOrchestration wrongInstance = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .id("other-orchestration")
                .build();
        assertThatThrownBy(() -> wrongInstance.resume(continuation, approve(continuation)))
                .isInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("belongs to orchestration");

        OrchestrationResult<AgentResponse<?>> resumed = orchestration.resume(continuation, approve(continuation));
        assertThat(resumed.outcome()).isEqualTo(OrchestrationOutcome.COMPLETED);

        orchestration.close();
        wrongPattern.participants().getFirst().agent().close();
        wrongPattern.close();
        wrongInstance.close();
        participant.close();
    }

    @Test
    void unsupportedApprovalSource_shouldFailBeforeReturningAnUnresumableContinuation() {
        ToolApprovalRequest request = new ToolApprovalRequest(
                new ToolApprovalId("approval"),
                "agent-run",
                new InvocationId("invocation"),
                "call",
                "tool",
                "schema",
                "arguments",
                "request",
                StateValue.object(Map.of()),
                ToolApprovalState.PENDING);
        AgentContinuation continuation =
                new AgentContinuation("agent-continuation", null, "agent-run", List.of(request), false, false);
        TestAgent unsupported = new TestAgent("unsupported", 0, (messages, options, invocation) -> {
            throw new ApprovalRequiredException(continuation, List.of());
        });
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(unsupported)))
                .build();

        assertThatThrownBy(() ->
                        orchestration.runAsync("start").toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OrchestrationContinuationException.class)
                .hasMessageContaining("does not support orchestration resume");
        assertThat(orchestration.pendingContinuationCountForDiagnostics()).isZero();

        orchestration.close();
        unsupported.close();
    }

    @Test
    void continuationStorage_shouldEvictExpireAndClearAbandonedStateOnClose() throws Exception {
        TestAgent participant = new TestAgent(
                "human",
                0,
                (messages, options, invocation) -> HandoffOrchestrationTest.directiveResponse(
                        "human",
                        "request_human_input",
                        StateValue.object(Map.of("prompt", StateValue.string("More input?")))));
        HandoffOrchestration orchestration = HandoffOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .continuationOptions(new OrchestrationContinuationOptions(Duration.ofMillis(500), 1))
                .build();

        OrchestrationContinuation first = orchestration.run("first").continuation();
        OrchestrationContinuation second = orchestration.run("second").continuation();

        assertThat(orchestration.pendingContinuationCountForDiagnostics()).isOne();
        assertThatThrownBy(() -> orchestration.resume(first, OrchestrationResumeInput.human("stale")))
                .isInstanceOf(OrchestrationContinuationException.class);

        Thread.sleep(650);
        assertThat(orchestration.pendingContinuationCountForDiagnostics()).isZero();
        assertThatThrownBy(() -> orchestration.resume(second, OrchestrationResumeInput.human("expired")))
                .isInstanceOf(OrchestrationContinuationException.class);

        OrchestrationContinuation abandoned = orchestration.run("third").continuation();
        assertThat(orchestration.pendingContinuationCountForDiagnostics()).isOne();
        orchestration.close();
        assertThat(orchestration.pendingContinuationCountForDiagnostics()).isZero();
        assertThatThrownBy(() -> orchestration.resume(abandoned, OrchestrationResumeInput.human("closed")))
                .isInstanceOf(OrchestrationExecutionException.class)
                .hasMessageContaining("closed");

        participant.close();
    }

    @Test
    void close_shouldDiscardUnderlyingStatelessAgentContinuation() {
        ChatAgent participant = approvalAgent("stateless", "unused", new AtomicInteger());
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .build();
        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run(
                "start",
                OrchestrationRunOptions.builder()
                        .sessionPolicy(OrchestrationSessionPolicy.STATELESS)
                        .build());
        OrchestrationContinuation continuation = suspended.continuation();
        OrchestrationResumeInput.Approval approval = approve(continuation);

        orchestration.close();

        assertThatThrownBy(() -> participant
                        .resumeAsync(continuation.agentContinuation(), approval.decisions())
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("stale");

        participant.close();
    }

    @Test
    void resumeCancellationAndStreaming_shouldPropagateAndContinueEventSequence() throws Exception {
        TestAgent slow = new TestAgent(
                "worker", 10_000, (messages, options, invocation) -> TestAgent.response("worker", "late"));
        MagenticOrchestration cancellable = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(slow)), new CompletingMagenticManager())
                .requirePlanReview(true)
                .build();
        OrchestrationResult<MagenticResult> suspended = cancellable.run("work");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        var handle = cancellable.startResume(
                suspended.continuation(),
                OrchestrationResumeInput.approvePlan(),
                OrchestrationRunOptions.defaults(),
                cancellation);
        slow.firstInvocation().orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(cancellation.cancel()).isTrue();
        assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);

        TestAgent worker = TestAgent.responding("worker", "done");
        MagenticOrchestration streaming = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), new CompletingMagenticManager())
                .requirePlanReview(true)
                .build();
        OrchestrationResult<MagenticResult> streamSuspended = streaming.run("work");
        TestEventSubscriber subscriber = new TestEventSubscriber(Long.MAX_VALUE);
        streaming
                .resumeStreaming(streamSuspended.continuation(), OrchestrationResumeInput.approvePlan())
                .subscribe(subscriber);
        List<OrchestrationEvent> events =
                subscriber.result().orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().sequence())
                .isEqualTo(streamSuspended.events().size());
        assertThat(events.getLast().type()).isEqualTo(OrchestrationEventType.RUN_COMPLETED);
        assertThat(subscriber.terminalSignals()).hasValue(1);

        cancellable.close();
        streaming.close();
        slow.close();
        worker.close();
    }

    @Test
    void approvalResumeCancellation_shouldReachTheUnderlyingAgentContinuation() {
        AtomicInteger toolInvocations = new AtomicInteger();
        CancellableApprovalChatClient client = new CancellableApprovalChatClient("approver");
        ChatAgent participant = approvalAgent("approver", client, toolInvocations);
        SequentialOrchestration orchestration = SequentialOrchestration.builder(
                        List.of(OrchestrationParticipant.of(participant)))
                .build();
        OrchestrationResult<AgentResponse<?>> suspended = orchestration.run("start");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        var handle = orchestration.startResume(
                suspended.continuation(),
                approve(suspended.continuation()),
                OrchestrationRunOptions.defaults(),
                cancellation);
        client.resumedProviderTurn.orTimeout(5, TimeUnit.SECONDS).join();

        assertThat(cancellation.cancel()).isTrue();

        assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(toolInvocations).hasValue(1);

        orchestration.close();
        participant.close();
    }

    @Test
    void resumeStreaming_shouldEnforceConfiguredEventBufferBound() {
        TestAgent worker = TestAgent.responding("worker", "done");
        MagenticOrchestration orchestration = MagenticOrchestration.builder(
                        List.of(OrchestrationParticipant.of(worker)), new CompletingMagenticManager())
                .requirePlanReview(true)
                .build();
        OrchestrationResult<MagenticResult> suspended = orchestration.run("work");
        TestEventSubscriber subscriber = new TestEventSubscriber(0);

        orchestration
                .resumeStreaming(
                        suspended.continuation(),
                        OrchestrationResumeInput.approvePlan(),
                        OrchestrationRunOptions.builder().maxBufferedEvents(1).build())
                .subscribe(subscriber);

        assertThatThrownBy(
                        () -> subscriber.result().orTimeout(5, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(OrchestrationStreamingBufferOverflowException.class);
        assertThat(subscriber.terminalSignals()).hasValue(1);

        orchestration.close();
        worker.close();
    }

    private static OrchestrationResumeInput.Approval approve(OrchestrationContinuation continuation) {
        return OrchestrationResumeInput.approval(List.of(ToolApprovalDecision.approve(
                continuation.agentContinuation().approvalRequests().getFirst())));
    }

    private static OrchestrationContinuation copy(
            OrchestrationContinuation continuation,
            String orchestrationId,
            String runId,
            OrchestrationPattern pattern,
            String participantId) {
        return new OrchestrationContinuation(
                continuation.continuationId(),
                orchestrationId,
                runId,
                pattern,
                continuation.kind(),
                participantId,
                continuation.agentContinuation(),
                continuation.transcript(),
                continuation.prompt(),
                false);
    }

    private static ChatAgent approvalAgent(String id, String finalText, AtomicInteger toolInvocations) {
        FunctionCallContent call = new FunctionCallContent(id + "-call", "write", StateValue.object(Map.of()));
        QueueChatClient client = new QueueChatClient(List.of(
                ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build(),
                ChatResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, finalText)))
                        .build()));
        return approvalAgent(id, client, toolInvocations);
    }

    private static ChatAgent approvalAgent(String id, ChatClient client, AtomicInteger toolInvocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "approval test tool",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        FunctionTool tool = FunctionTool.create(metadata, (context, arguments) -> {
            toolInvocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
        return new ChatAgent(
                client, new AgentMetadata(id, id, "approval test agent"), ChatOptions.empty(), List.of(tool));
    }

    private static final class QueueChatClient implements ChatClient {
        private final ArrayDeque<ChatResponse> responses;

        private QueueChatClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public synchronized CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest request, RunCancellation cancellation) {
            if (responses.isEmpty()) {
                return CompletableFuture.failedFuture(new AssertionError("No queued chat response."));
            }
            return CompletableFuture.completedFuture(responses.removeFirst());
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
                public void cancel() {
                    cancellation.cancel();
                }
            });
        }
    }

    private static final class CancellableApprovalChatClient implements ChatClient {
        private final String id;

        private final AtomicInteger calls = new AtomicInteger();

        private final CompletableFuture<Void> resumedProviderTurn = new CompletableFuture<>();

        private CancellableApprovalChatClient(String id) {
            this.id = id;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            if (calls.getAndIncrement() == 0) {
                FunctionCallContent call = new FunctionCallContent(id + "-call", "write", StateValue.object(Map.of()));
                return CompletableFuture.completedFuture(ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build());
            }
            resumedProviderTurn.complete(null);
            CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
            cancellation
                    .cancelledAsync()
                    .whenComplete((ignored, failure) -> pending.completeExceptionally(new RunCancelledException()));
            return pending;
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            throw new AssertionError("Streaming provider calls are not expected.");
        }
    }

    private static final class CompletingMagenticManager implements MagenticManager {
        private final AtomicInteger planCalls = new AtomicInteger();

        private final AtomicInteger replanCalls = new AtomicInteger();

        @Override
        public CompletionStage<MagenticPlan> planAsync(MagenticContext context) {
            planCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new MagenticPlan(
                    0, "reviewed plan", List.of(MagenticTask.pending("work", "Do the work", "worker"))));
        }

        @Override
        public CompletionStage<MagenticPlan> replanAsync(MagenticContext context) {
            replanCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new MagenticPlan(
                    1, "revised plan", List.of(MagenticTask.pending("work", "Retry the work", "worker"))));
        }

        @Override
        public CompletionStage<MagenticProgressAssessment> assessProgressAsync(MagenticContext context) {
            return CompletableFuture.completedFuture(
                    new MagenticProgressAssessment(true, true, false, null, null, "complete"));
        }

        @Override
        public CompletionStage<AgentResponse<?>> prepareFinalAnswerAsync(MagenticContext context) {
            return CompletableFuture.completedFuture(TestAgent.response("manager", "final"));
        }
    }
}
