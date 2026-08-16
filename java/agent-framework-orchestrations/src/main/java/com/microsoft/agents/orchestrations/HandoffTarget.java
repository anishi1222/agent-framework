// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Describes one registered handoff target.
 *
 * @param participantId registered participant identifier
 * @param description optional routing description
 */
public record HandoffTarget(String participantId, String description) {
    /** Creates a validated immutable target. */
    public HandoffTarget {
        participantId = OrchestrationValidation.requireId(participantId, "participantId");
        description = OrchestrationValidation.optionalText(description, "description");
    }
}
