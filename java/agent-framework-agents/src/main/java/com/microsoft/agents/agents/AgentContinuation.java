// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.tools.ToolApprovalRequest;
import java.util.List;

/**
 * Identifies immutable approval authority for one suspended logical agent run.
 *
 * @param continuationId opaque one-time continuation identifier
 * @param sessionId owning session identity, or {@code null} for an explicit process-local
 *     continuation
 * @param logicalRunId uninterrupted logical run identity
 * @param approvalRequests pending approval requests in tool-call order
 * @param restartCapable whether safe pending state was persisted in a configured session store
 * @param exactlyOnceAfterRestart whether a durable invocation ledger or provider-idempotency
 *     capability supports that stronger claim
 */
public record AgentContinuation(
        String continuationId,
        String sessionId,
        String logicalRunId,
        List<ToolApprovalRequest> approvalRequests,
        boolean restartCapable,
        boolean exactlyOnceAfterRestart) {
    /** Creates a validated immutable continuation descriptor. */
    public AgentContinuation {
        continuationId = AgentValidation.requireNonBlank(continuationId, "continuationId");
        sessionId = AgentValidation.optionalNonBlank(sessionId, "sessionId");
        logicalRunId = AgentValidation.requireNonBlank(logicalRunId, "logicalRunId");
        approvalRequests = List.copyOf(AgentValidation.requireNonNull(approvalRequests, "approvalRequests"));
        if (approvalRequests.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException("approvalRequests must not be empty.");
        }
        if (approvalRequests.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("approvalRequests contains null");
        }
        if (exactlyOnceAfterRestart && !restartCapable) {
            throw new com.microsoft.agents.core.ValidationException("exactlyOnceAfterRestart requires restartCapable.");
        }
    }
}
