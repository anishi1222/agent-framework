// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/**
 * Represents one client decision bound to a pending approval identifier.
 *
 * @param approvalId exact pending approval identifier
 * @param approved whether the request is approved
 * @param reason optional rejection reason
 */
public record HostingApprovalDecision(String approvalId, boolean approved, String reason) {
    /** Creates a validated decision. */
    public HostingApprovalDecision {
        approvalId = HostingValidation.nonBlank(approvalId, "approvalId");
        reason = HostingValidation.optionalNonBlank(reason, "reason");
        if (approved && reason != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "An approved decision must not include a rejection reason.");
        }
    }
}
