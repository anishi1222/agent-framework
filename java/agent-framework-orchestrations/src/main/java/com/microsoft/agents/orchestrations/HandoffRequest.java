// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Requests transfer to one registered handoff target.
 *
 * @param targetId registered target identifier
 * @param reason optional routing reason
 */
public record HandoffRequest(String targetId, String reason) implements HandoffDirective {
    /** Creates a validated request. */
    public HandoffRequest {
        targetId = OrchestrationValidation.requireId(targetId, "targetId");
        reason = OrchestrationValidation.optionalText(reason, "reason");
    }
}
