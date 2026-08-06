// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Captures safe data needed to reconstruct one pending function call.
 *
 * @param call provider function-call content
 * @param invocationId stable invocation identity
 * @param requestDigest exact request digest
 * @param arguments normalized JSON arguments, or {@code null} when preparation failed
 * @param preparationError sanitized preparation failure, or {@code null}
 * @param duplicate whether this call occurrence was already owned earlier in the logical run
 * @param approvalRequest pending approval authority, or {@code null}
 * @param approvalDecision accepted but not yet consumed decision, or {@code null}
 */
public record FunctionContinuationCall(
        FunctionCallContent call,
        InvocationId invocationId,
        String requestDigest,
        StateValue.ObjectValue arguments,
        String preparationError,
        boolean duplicate,
        ToolApprovalRequest approvalRequest,
        ToolApprovalDecision approvalDecision) {
    /** Creates a validated immutable pending-call snapshot. */
    public FunctionContinuationCall {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(invocationId, "invocationId");
        requestDigest = ToolValidation.requireNonBlank(requestDigest, "requestDigest");
        preparationError = ToolValidation.optionalNonBlank(preparationError, "preparationError");
        if (arguments == null && preparationError == null) {
            throw new IllegalArgumentException("arguments may be absent only when preparationError is present.");
        }
        if (approvalRequest != null
                && (!approvalRequest.invocationId().equals(invocationId)
                        || !approvalRequest.requestDigest().equals(requestDigest))) {
            throw new IllegalArgumentException("approvalRequest must match the pending invocation and request digest.");
        }
        if (approvalDecision != null
                && (approvalRequest == null
                        || !approvalDecision.approvalId().equals(approvalRequest.approvalId())
                        || !approvalDecision.invocationId().equals(invocationId)
                        || !approvalDecision.requestDigest().equals(requestDigest))) {
            throw new IllegalArgumentException("approvalDecision must match the pending approval request.");
        }
    }
}
