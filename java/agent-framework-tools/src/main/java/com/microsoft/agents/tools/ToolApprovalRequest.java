// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents immutable pending authority for one exact tool invocation.
 *
 * @param approvalId stable approval identifier
 * @param logicalRunId uninterrupted logical run identifier
 * @param invocationId invocation identifier
 * @param callId provider call correlation identifier
 * @param toolName exact tool name presented for approval
 * @param schemaDigest digest of the exact input schema
 * @param argumentsDigest digest of the exact arguments
 * @param requestDigest digest binding run, call, invocation, tool, schema, and arguments
 * @param arguments immutable JSON-shaped arguments
 * @param state approval state, always {@link ToolApprovalState#PENDING}
 */
public record ToolApprovalRequest(
        ToolApprovalId approvalId,
        String logicalRunId,
        InvocationId invocationId,
        String callId,
        String toolName,
        String schemaDigest,
        String argumentsDigest,
        String requestDigest,
        StateValue.ObjectValue arguments,
        ToolApprovalState state) {
    /** Creates a validated immutable approval request. */
    public ToolApprovalRequest {
        Objects.requireNonNull(approvalId, "approvalId");
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        Objects.requireNonNull(invocationId, "invocationId");
        callId = ToolValidation.requireNonBlank(callId, "callId");
        toolName = ToolValidation.requireNonBlank(toolName, "toolName");
        schemaDigest = ToolValidation.requireNonBlank(schemaDigest, "schemaDigest");
        argumentsDigest = ToolValidation.requireNonBlank(argumentsDigest, "argumentsDigest");
        requestDigest = ToolValidation.requireNonBlank(requestDigest, "requestDigest");
        Objects.requireNonNull(arguments, "arguments");
        if (state != ToolApprovalState.PENDING) {
            throw new IllegalArgumentException("An approval request must be pending.");
        }
    }
}
