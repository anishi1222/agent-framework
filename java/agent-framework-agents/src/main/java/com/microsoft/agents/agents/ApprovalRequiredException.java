// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.tools.ToolApprovalDecisionRejection;
import java.util.List;

/**
 * Signals that a no-session or legacy response-only run requires explicit approval continuation.
 *
 * <p>Session-aware APIs convert this typed boundary into {@link AgentRunResult#inputRequired}. The
 * exception is not a generic execution failure and retains a one-time continuation descriptor.
 */
public final class ApprovalRequiredException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    private final transient AgentContinuation continuation;

    private final transient List<ToolApprovalDecisionRejection> rejectedDecisions;

    /**
     * Creates an approval-required boundary.
     *
     * @param continuation pending continuation
     * @param rejectedDecisions correlated rejected decisions
     */
    public ApprovalRequiredException(
            AgentContinuation continuation, List<ToolApprovalDecisionRejection> rejectedDecisions) {
        super("Agent run requires tool approval continuation.");
        this.continuation = AgentValidation.requireNonNull(continuation, "continuation");
        this.rejectedDecisions = List.copyOf(AgentValidation.requireNonNull(rejectedDecisions, "rejectedDecisions"));
    }

    /**
     * Returns the pending continuation.
     *
     * @return continuation descriptor
     */
    public AgentContinuation continuation() {
        return continuation;
    }

    /**
     * Returns correlated rejected decisions.
     *
     * @return immutable rejection list
     */
    public List<ToolApprovalDecisionRejection> rejectedDecisions() {
        return rejectedDecisions;
    }
}
