// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Describes an opaque, process-local, one-time continuation.
 *
 * @param token unguessable token
 * @param type continuation type
 * @param expiresAt expiry instant
 * @param approvalRequests pending approvals, empty for non-approval continuations
 */
public record HostingContinuationDescriptor(
        String token, HostingContinuationType type, Instant expiresAt, List<HostingApprovalRequest> approvalRequests) {
    /** Creates a validated immutable descriptor. */
    public HostingContinuationDescriptor {
        token = HostingValidation.nonBlank(token, "token");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expiresAt, "expiresAt");
        approvalRequests = HostingValidation.copyList(approvalRequests, "approvalRequests");
        if ((type == HostingContinuationType.APPROVAL) != !approvalRequests.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Approval requests must be present exactly for an approval continuation.");
        }
    }
}
