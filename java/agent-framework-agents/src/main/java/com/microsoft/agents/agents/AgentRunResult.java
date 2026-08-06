// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.tools.ToolApprovalDecisionRejection;
import java.util.List;
import java.util.Optional;

/**
 * Represents either a completed session-aware run or an explicit approval continuation.
 *
 * @param <T> structured response value type
 */
public final class AgentRunResult<T> {
    private final AgentRunOutcome outcome;

    private final AgentResponse<T> response;

    private final AgentContinuation continuation;

    private final List<ToolApprovalDecisionRejection> rejectedDecisions;

    private AgentRunResult(
            AgentRunOutcome outcome,
            AgentResponse<T> response,
            AgentContinuation continuation,
            List<ToolApprovalDecisionRejection> rejectedDecisions) {
        this.outcome = AgentValidation.requireNonNull(outcome, "outcome");
        this.response = response;
        this.continuation = continuation;
        this.rejectedDecisions = List.copyOf(rejectedDecisions);
        if ((response == null) == (continuation == null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Exactly one of response or continuation must be present.");
        }
    }

    /**
     * Creates a completed result.
     *
     * @param response terminal response
     * @param rejectedDecisions correlated decisions rejected during resume
     * @param <T> structured response type
     * @return completed result
     */
    public static <T> AgentRunResult<T> completed(
            AgentResponse<T> response, List<ToolApprovalDecisionRejection> rejectedDecisions) {
        return new AgentRunResult<>(
                AgentRunOutcome.COMPLETED,
                AgentValidation.requireNonNull(response, "response"),
                null,
                AgentValidation.requireNonNull(rejectedDecisions, "rejectedDecisions"));
    }

    /**
     * Creates an input-required result.
     *
     * @param continuation pending continuation
     * @param rejectedDecisions correlated decisions rejected during resume
     * @param <T> structured response type
     * @return input-required result
     */
    public static <T> AgentRunResult<T> inputRequired(
            AgentContinuation continuation, List<ToolApprovalDecisionRejection> rejectedDecisions) {
        return new AgentRunResult<>(
                AgentRunOutcome.INPUT_REQUIRED,
                null,
                AgentValidation.requireNonNull(continuation, "continuation"),
                AgentValidation.requireNonNull(rejectedDecisions, "rejectedDecisions"));
    }

    /**
     * Returns the phase outcome.
     *
     * @return outcome
     */
    public AgentRunOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the terminal response when completed.
     *
     * @return optional response
     */
    public Optional<AgentResponse<T>> response() {
        return Optional.ofNullable(response);
    }

    /**
     * Returns the continuation when input is required.
     *
     * @return optional continuation
     */
    public Optional<AgentContinuation> continuation() {
        return Optional.ofNullable(continuation);
    }

    /**
     * Returns decisions rejected while processing this phase.
     *
     * @return immutable correlated rejection list
     */
    public List<ToolApprovalDecisionRejection> rejectedDecisions() {
        return rejectedDecisions;
    }
}
