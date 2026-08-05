// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Creates stable approval requests bound to exact invocation inputs.
 */
public final class ToolApprovals {
    private ToolApprovals() {}

    /**
     * Creates a pending approval request for one function invocation.
     *
     * @param context invocation context
     * @param tool function tool
     * @param arguments exact immutable arguments
     * @return stable pending request
     */
    public static ToolApprovalRequest request(
            ToolInvocationContext context, FunctionTool tool, StateValue.ObjectValue arguments) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
        String schemaDigest = ToolDigests.state(tool.metadata().inputSchema());
        String argumentsDigest = ToolDigests.state(arguments);
        String requestDigest = ToolDigests.strings(
                context.logicalRunId(),
                context.callId(),
                context.invocationId().value(),
                tool.name(),
                schemaDigest,
                argumentsDigest);
        ToolApprovalId approvalId = new ToolApprovalId("approval-" + requestDigest);
        return new ToolApprovalRequest(
                approvalId,
                context.logicalRunId(),
                context.invocationId(),
                context.callId(),
                tool.name(),
                schemaDigest,
                argumentsDigest,
                requestDigest,
                arguments,
                ToolApprovalState.PENDING);
    }
}
