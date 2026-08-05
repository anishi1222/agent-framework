// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    void rejectedResume_shouldNeverInvokeAndShouldNotCreateFunctionResult() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = guardedTool(invocations);
        ScriptedToolTurnSource source = new ScriptedToolTurnSource().enqueue(response(call("call-rejected")));

        // Act
        try (FunctionInvocationLoop loop = new FunctionInvocationLoop(source, List.of(tool))) {
            FunctionLoopResult suspended =
                    loop.run(new FunctionInvocationRequest("run-rejected", List.of(Message.text(Role.USER, "write"))));
            FunctionLoopResult rejected = loop.resume(
                            suspended,
                            List.of(ToolApprovalDecision.reject(
                                    suspended.approvalRequests().getFirst(), "user rejected")))
                    .result();

            // Assert
            assertThat(rejected.outcome()).isEqualTo(FunctionLoopOutcome.SUCCESS);
            assertThat(rejected.assistantText()).contains("The tool was not executed.");
            assertThat(invocations).hasValue(0);
            assertThat(rejected.history().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance))
                    .isEmpty();
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
        return FunctionTool.create(
                new ToolMetadata(
                        "write",
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
