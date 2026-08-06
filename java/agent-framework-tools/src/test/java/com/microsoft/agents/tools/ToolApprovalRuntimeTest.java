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
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolApprovalRuntimeTest {
    @Test
    void request_shouldHaveStableDigestBoundToRunCallInvocationToolSchemaAndArguments() {
        // Arrange
        FunctionTool tool = guardedTool(new AtomicInteger());
        StateValue.ObjectValue arguments = StateValue.object(Map.of("value", StateValue.string("one")));
        ToolInvocationContext context = new ToolInvocationContext(
                "logical-run",
                "call-1",
                new InvocationId("logical-run:call-1"),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());

        // Act
        ToolApprovalRequest first = ToolApprovals.request(context, tool, arguments);
        ToolApprovalRequest second = ToolApprovals.request(context, tool, arguments);
        ToolApprovalRequest changed =
                ToolApprovals.request(context, tool, StateValue.object(Map.of("value", StateValue.string("two"))));

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.state()).isEqualTo(ToolApprovalState.PENDING);
        assertThat(first.approvalId()).isNotEqualTo(changed.approvalId());
        assertThat(first.requestDigest()).isNotEqualTo(changed.requestDigest());
    }

    @Test
    void approvedResume_shouldInvokeExactlyOnceAndRejectStaleReplay() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-approval")))
                .enqueue(response(Message.text(Role.ASSISTANT, "completed")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-approval", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalRequest request = suspended.approvalRequests().getFirst();
            FunctionLoopResult resumed = loop.resume(suspended, List.of(ToolApprovalDecision.approve(request)))
                    .result();

            // Assert
            assertThat(suspended.outcome()).isEqualTo(FunctionLoopOutcome.INPUT_REQUIRED);
            assertThat(invocations).hasValue(1);
            assertThat(resumed.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
            assertThat(resumed.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance))
                    .hasSize(1);
            assertThatThrownBy(() -> loop.resume(suspended, List.of(ToolApprovalDecision.approve(request)))
                            .resultAsync()
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .rootCause()
                    .isInstanceOf(ToolInvocationException.class)
                    .hasMessageContaining("stale");
            assertThat(invocations).hasValue(1);
        }
    }

    @Test
    void rejectedStreamingResume_shouldEmitOneCorrelatedTerminalResultWithoutInvoking() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(response(call("call-rejected")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-rejected", List.of(Message.text(Role.USER, "write"))));
            FunctionInvocationRun run = loop.resumeStreaming(
                    suspended,
                    List.of(ToolApprovalDecision.reject(
                            suspended.approvalRequests().getFirst(), "user rejected")));
            List<ChatResponseUpdate> updates = collect(run.updates()).join();
            FunctionLoopResult rejected = run.result();

            // Assert
            assertThat(rejected.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
            assertThat(invocations).hasValue(0);
            assertThat(rejected.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance)
                            .map(FunctionResultContent.class::cast))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.callId()).isEqualTo("call-rejected");
                        assertThat(result.result()).isEqualTo(StateValue.string("The tool was not executed."));
                        assertThat(result.metadata())
                                .containsEntry("invocationId", StateValue.string("run-rejected:call-rejected"))
                                .containsEntry("outcome", StateValue.string("rejected"));
                    });
            assertThat(updates.stream()
                            .flatMap(update -> update.contents().stream())
                            .filter(FunctionResultContent.class::isInstance))
                    .hasSize(1);
        }
    }

    @Test
    void mixedStreamingResume_shouldEmitApprovedAndRejectedResultsInModelOrder() {
        // Arrange
        AtomicInteger approvedInvocations = new AtomicInteger();
        AtomicInteger rejectedInvocations = new AtomicInteger();
        FunctionTool approvedTool = guardedTool("approved", approvedInvocations);
        FunctionTool rejectedTool = guardedTool("rejected", rejectedInvocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(new Message(
                        Role.ASSISTANT,
                        List.of(
                                new FunctionCallContent(
                                        "call-approved",
                                        approvedTool.name(),
                                        StateValue.object(Map.of("value", StateValue.string("one")))),
                                new FunctionCallContent(
                                        "call-rejected",
                                        rejectedTool.name(),
                                        StateValue.object(Map.of("value", StateValue.string("two"))))))))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(approvedTool, rejectedTool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-mixed", List.of(Message.text(Role.USER, "both"))));
            FunctionInvocationRun run = loop.resumeStreaming(
                    suspended,
                    List.of(
                            ToolApprovalDecision.approve(
                                    suspended.approvalRequests().get(0)),
                            ToolApprovalDecision.reject(
                                    suspended.approvalRequests().get(1), "declined")));
            List<ChatResponseUpdate> updates = collect(run.updates()).join();
            FunctionLoopResult completed = run.result();

            // Assert
            assertThat(approvedInvocations).hasValue(1);
            assertThat(rejectedInvocations).hasValue(0);
            List<FunctionResultContent> historyResults = functionResults(completed);
            assertThat(historyResults)
                    .extracting(FunctionResultContent::callId)
                    .containsExactly("call-approved", "call-rejected");
            assertThat(historyResults)
                    .extracting(result -> result.metadata().get("outcome"))
                    .containsExactly(StateValue.string("succeeded"), StateValue.string("rejected"));
            assertThat(updates.stream()
                            .flatMap(update -> update.contents().stream())
                            .filter(FunctionResultContent.class::isInstance)
                            .map(FunctionResultContent.class::cast))
                    .extracting(FunctionResultContent::callId)
                    .containsExactly("call-approved", "call-rejected");
        }
    }

    @Test
    void redundantDecisionAfterPrimaryRejection_shouldRemainRejectedAndNeverExecute() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source =
                new ScriptedToolTurnSource().enqueue(response(call("call-redundant-rejection")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended = loop.run(new FunctionInvocationRequest(
                    "run-redundant-rejection", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalRequest request = suspended.approvalRequests().getFirst();
            FunctionLoopResult completed = loop.resume(
                            suspended,
                            List.of(
                                    ToolApprovalDecision.reject(request, "primary rejection"),
                                    ToolApprovalDecision.approve(request)))
                    .result();

            // Assert
            assertThat(invocations).hasValue(0);
            assertThat(completed.rejectedDecisions())
                    .extracting(ToolApprovalDecisionRejection::reason)
                    .containsExactly(ToolApprovalDecisionRejectionReason.DECISION_ALREADY_PENDING);
            assertThat(functionResults(completed)).singleElement().satisfies(result -> assertThat(result.metadata())
                    .containsEntry("outcome", StateValue.string("rejected")));
        }
    }

    @Test
    void duplicateRejectedCallOccurrences_shouldReuseOneTerminalResultAndNeverExecute() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        FunctionCallContent call = call("call-duplicate-rejected");
        ScriptedToolTurnSource source =
                new ScriptedToolTurnSource().enqueue(response(new Message(Role.ASSISTANT, List.of(call, call))));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended = loop.run(
                    new FunctionInvocationRequest("run-duplicate-rejected", List.of(Message.text(Role.USER, "write"))));
            FunctionLoopResult completed = loop.resume(
                            suspended,
                            List.of(ToolApprovalDecision.reject(
                                    suspended.approvalRequests().getFirst(), "declined")))
                    .result();

            // Assert
            assertThat(invocations).hasValue(0);
            assertThat(functionResults(completed)).singleElement().satisfies(result -> assertThat(result.metadata())
                    .containsEntry("outcome", StateValue.string("rejected")));
        }
    }

    @Test
    void mismatchedDecision_shouldRemainPendingAndAllowLaterValidResume() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-mismatch")))
                .enqueue(response(Message.text(Role.ASSISTANT, "done")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-mismatch", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalRequest request = suspended.approvalRequests().getFirst();
            ToolApprovalDecision forged = new ToolApprovalDecision(
                    request.approvalId(), request.invocationId(), "different-digest", ToolApprovalState.APPROVED, null);
            FunctionLoopResult stillPending =
                    loop.resume(suspended, List.of(forged)).result();
            FunctionLoopResult completed = loop.resume(stillPending, List.of(ToolApprovalDecision.approve(request)))
                    .result();

            // Assert
            assertThat(stillPending.outcome()).isEqualTo(FunctionLoopOutcome.INPUT_REQUIRED);
            assertThat(stillPending.rejectedDecisions())
                    .extracting(ToolApprovalDecisionRejection::reason)
                    .containsExactly(ToolApprovalDecisionRejectionReason.MISMATCHED_REQUEST);
            assertThat(invocations).hasValue(1);
            assertThat(completed.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
        }
    }

    @Test
    void duplicatePendingDecisions_shouldRejectSecondButExecutePrimaryExactlyOnce() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-duplicate-decision")))
                .enqueue(response(Message.text(Role.ASSISTANT, "written")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended = loop.run(
                    new FunctionInvocationRequest("run-duplicate-decision", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalRequest request = suspended.approvalRequests().getFirst();
            FunctionLoopResult completed = loop.resume(
                            suspended,
                            List.of(
                                    ToolApprovalDecision.approve(request),
                                    ToolApprovalDecision.reject(request, "late duplicate")))
                    .result();

            // Assert
            assertThat(invocations).hasValue(1);
            assertThat(completed.rejectedDecisions())
                    .extracting(ToolApprovalDecisionRejection::reason)
                    .containsExactly(ToolApprovalDecisionRejectionReason.DECISION_ALREADY_PENDING);
            assertThat(completed.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance))
                    .hasSize(1);
        }
    }

    @Test
    void concurrentResumeRace_shouldConsumeAuthorityExactlyOnce() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource()
                .enqueue(response(call("call-race")))
                .enqueue(response(Message.text(Role.ASSISTANT, "done")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-race", List.of(Message.text(Role.USER, "write"))));
            ToolApprovalDecision decision =
                    ToolApprovalDecision.approve(suspended.approvalRequests().getFirst());
            FunctionInvocationRun first = loop.resume(suspended, List.of(decision));
            FunctionInvocationRun second = loop.resume(suspended, List.of(decision));
            CompletableFuture<FunctionLoopResult> firstResult =
                    first.resultAsync().toCompletableFuture();
            CompletableFuture<FunctionLoopResult> secondResult =
                    second.resultAsync().toCompletableFuture();

            // Assert
            assertThat(firstResult.handle((value, failure) -> failure == null).join()
                            ^ secondResult
                                    .handle((value, failure) -> failure == null)
                                    .join())
                    .isTrue();
            assertThat(invocations).hasValue(1);
        }
    }

    private static FunctionTool guardedTool(AtomicInteger invocations) {
        return guardedTool("write", invocations);
    }

    private static FunctionTool guardedTool(String name, AtomicInteger invocations) {
        return FunctionTool.create(
                new ToolMetadata(
                        name,
                        "Writes a value.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.ALWAYS_REQUIRE,
                        StateValue.object(Map.of(
                                "type",
                                StateValue.string("object"),
                                "properties",
                                StateValue.object(Map.of(
                                        "value", StateValue.object(Map.of("type", StateValue.string("string"))))),
                                "required",
                                StateValue.array(List.of(StateValue.string("value"))),
                                "additionalProperties",
                                StateValue.bool(false))),
                        StateValue.object(Map.of("type", StateValue.string("string")))),
                (context, arguments) -> {
                    invocations.incrementAndGet();
                    return CompletableFuture.completedFuture(StateValue.string("written"));
                });
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

    private static FunctionCallContent call(String callId) {
        return new FunctionCallContent(callId, "write", StateValue.object(Map.of("value", StateValue.string("once"))));
    }

    private static ChatResponse response(FunctionCallContent call) {
        return response(new Message(Role.ASSISTANT, List.of(call)));
    }

    private static ChatResponse response(Message message) {
        return ChatResponse.builder().messages(List.of(message)).build();
    }
}
