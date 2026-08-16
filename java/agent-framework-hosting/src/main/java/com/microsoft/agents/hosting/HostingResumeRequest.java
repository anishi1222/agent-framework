// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Represents one attempt to consume an opaque process-local continuation.
 *
 * @param token opaque one-time token
 * @param type expected continuation type
 * @param decisions approval decisions
 * @param input optional application input
 */
public record HostingResumeRequest(
        String token, HostingContinuationType type, List<HostingApprovalDecision> decisions, StateValue input) {
    /** Creates a validated immutable resume request. */
    public HostingResumeRequest {
        token = HostingValidation.nonBlank(token, "token");
        java.util.Objects.requireNonNull(type, "type");
        decisions = HostingValidation.copyList(decisions, "decisions");
    }
}
