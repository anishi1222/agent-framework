// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalRequest;
import java.util.concurrent.CompletionStage;

/** Decides the bundled approval request for one exact CodeAct program. */
@FunctionalInterface
public interface CodeActApprovalHandler {
    /**
     * Requests a decision bound to the supplied immutable approval request.
     *
     * @param request exact pending approval request
     * @param cancellation caller-owned cancellation signal
     * @return stage producing an approving or rejecting decision
     */
    CompletionStage<ToolApprovalDecision> requestApprovalAsync(
            ToolApprovalRequest request, RunCancellation cancellation);
}
