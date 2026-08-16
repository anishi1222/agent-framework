// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;

/**
 * Represents one response to an open interrupt.
 *
 * @param interruptId interrupt identifier
 * @param status resolution status
 * @param payload optional resolution payload
 */
public record AGUIResumeEntry(String interruptId, AGUIResumeStatus status, StateValue payload) {
    /** Creates a validated resume entry. */
    public AGUIResumeEntry {
        interruptId = AGUIValidation.nonBlank(interruptId, "interruptId");
        java.util.Objects.requireNonNull(status, "status");
        if (status == AGUIResumeStatus.CANCELLED && payload != null) {
            throw AGUIValidation.invalid("Cancelled resume entries must omit payload.");
        }
    }
}
