// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.Objects;

/**
 * Represents an immutable snapshot of one function-loop phase.
 */
public final class FunctionLoopResult {
    private final FunctionLoopOutcome outcome;

    private final String logicalRunId;

    private final List<Message> history;

    private final List<ToolApprovalRequest> approvalRequests;

    private final List<ToolApprovalDecisionRejection> rejectedDecisions;

    private final int modelTurns;

    private final int toolInvocations;

    final FunctionInvocationLoop.LogicalRunState state;

    final long suspensionVersion;

    FunctionLoopResult(
            FunctionLoopOutcome outcome,
            String logicalRunId,
            List<Message> history,
            List<ToolApprovalRequest> approvalRequests,
            List<ToolApprovalDecisionRejection> rejectedDecisions,
            int modelTurns,
            int toolInvocations,
            FunctionInvocationLoop.LogicalRunState state,
            long suspensionVersion) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        this.history = List.copyOf(history);
        this.approvalRequests = List.copyOf(approvalRequests);
        this.rejectedDecisions = List.copyOf(rejectedDecisions);
        this.modelTurns = modelTurns;
        this.toolInvocations = toolInvocations;
        this.state = Objects.requireNonNull(state, "state");
        this.suspensionVersion = suspensionVersion;
    }

    /**
     * Returns this phase's outcome.
     *
     * @return phase outcome
     */
    public FunctionLoopOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the logical run identifier.
     *
     * @return logical run identifier
     */
    public String logicalRunId() {
        return logicalRunId;
    }

    /**
     * Returns immutable ordered model history.
     *
     * @return ordered history
     */
    public List<Message> history() {
        return history;
    }

    /**
     * Returns immutable pending approval requests in call order.
     *
     * @return pending approval requests
     */
    public List<ToolApprovalRequest> approvalRequests() {
        return approvalRequests;
    }

    /**
     * Returns decisions that were explicitly rejected while processing this phase.
     *
     * @return immutable rejected-decision list
     */
    public List<ToolApprovalDecisionRejection> rejectedDecisions() {
        return rejectedDecisions;
    }

    /**
     * Returns the number of completed provider turns in this logical run.
     *
     * @return provider turn count
     */
    public int modelTurns() {
        return modelTurns;
    }

    /**
     * Returns the number of tool bodies started in this logical run.
     *
     * @return invocation count
     */
    public int toolInvocations() {
        return toolInvocations;
    }

    /**
     * Returns the final assistant text joined from history.
     *
     * @return assistant text
     */
    public String assistantText() {
        return history.stream()
                .filter(message -> message.role().equals(com.microsoft.agents.core.Role.ASSISTANT))
                .map(Message::text)
                .filter(text -> !text.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
