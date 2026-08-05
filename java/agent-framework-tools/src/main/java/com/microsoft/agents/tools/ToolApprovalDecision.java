// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.Objects;

/**
 * Represents an immutable decision bound to one exact approval request.
 *
 * @param approvalId approval identifier
 * @param invocationId invocation identifier
 * @param requestDigest exact request digest
 * @param state approved or rejected state
 * @param reason optional human-readable reason
 */
public record ToolApprovalDecision(
        ToolApprovalId approvalId,
        InvocationId invocationId,
        String requestDigest,
        ToolApprovalState state,
        String reason) {
    /** Creates a validated immutable approval decision. */
    public ToolApprovalDecision {
        Objects.requireNonNull(approvalId, "approvalId");
        Objects.requireNonNull(invocationId, "invocationId");
        requestDigest = ToolValidation.requireNonBlank(requestDigest, "requestDigest");
        Objects.requireNonNull(state, "state");
        if (state == ToolApprovalState.PENDING) {
            throw new IllegalArgumentException("An approval decision cannot be pending.");
        }
        reason = ToolValidation.optionalNonBlank(reason, "reason");
    }

    /**
     * Creates an approving decision for a request.
     *
     * @param request pending request
     * @return approving decision
     */
    public static ToolApprovalDecision approve(ToolApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        return new ToolApprovalDecision(
                request.approvalId(),
                request.invocationId(),
                request.requestDigest(),
                ToolApprovalState.APPROVED,
                null);
    }

    /**
     * Creates a rejecting decision for a request.
     *
     * @param request pending request
     * @param reason optional rejection reason
     * @return rejecting decision
     */
    public static ToolApprovalDecision reject(ToolApprovalRequest request, String reason) {
        Objects.requireNonNull(request, "request");
        return new ToolApprovalDecision(
                request.approvalId(),
                request.invocationId(),
                request.requestDigest(),
                ToolApprovalState.REJECTED,
                reason);
    }
}
