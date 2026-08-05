// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.util.Objects;

/**
 * Describes an explicit rejected approval decision.
 *
 * @param decision rejected decision
 * @param reason stable rejection reason
 * @param message diagnostic description
 */
public record ToolApprovalDecisionRejection(
        ToolApprovalDecision decision, ToolApprovalDecisionRejectionReason reason, String message) {
    /** Creates a validated immutable decision rejection. */
    public ToolApprovalDecisionRejection {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reason, "reason");
        message = ToolValidation.requireNonBlank(message, "message");
    }
}
