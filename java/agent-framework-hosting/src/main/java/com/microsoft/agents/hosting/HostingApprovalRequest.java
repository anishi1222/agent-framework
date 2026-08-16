// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Describes a pending approval without exposing provider or execution implementation types.
 *
 * @param approvalId stable approval identifier
 * @param callId provider tool-call correlation identifier
 * @param toolName tool name
 * @param arguments redacted immutable arguments
 */
public record HostingApprovalRequest(
        String approvalId, String callId, String toolName, StateValue.ObjectValue arguments) {
    /** Creates a validated immutable approval request. */
    public HostingApprovalRequest {
        approvalId = HostingValidation.nonBlank(approvalId, "approvalId");
        callId = HostingValidation.nonBlank(callId, "callId");
        toolName = HostingValidation.nonBlank(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
    }

    /**
     * Creates a compatibility request when the approval and call identifiers are identical.
     *
     * @param approvalId approval and call identifier
     * @param toolName tool name
     * @param arguments redacted arguments
     */
    public HostingApprovalRequest(String approvalId, String toolName, StateValue.ObjectValue arguments) {
        this(approvalId, approvalId, toolName, arguments);
    }
}
