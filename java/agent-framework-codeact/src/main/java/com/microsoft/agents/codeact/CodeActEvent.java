// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents one immutable deterministically sequenced CodeAct event.
 *
 * @param sequence zero-based sequence within the run
 * @param eventId stable identifier derived from the run and sequence
 * @param type event type
 * @param runId deterministic logical run identifier
 * @param stepIndex zero-based step index, or {@code -1} for run-level events
 * @param stepId optional step identifier
 * @param data immutable JSON-shaped event data
 */
public record CodeActEvent(
        long sequence,
        String eventId,
        CodeActEventType type,
        String runId,
        int stepIndex,
        String stepId,
        StateValue.ObjectValue data) {
    /** Creates a validated immutable event. */
    public CodeActEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative.");
        }
        eventId = CodeActValidation.requireNonBlank(eventId, "eventId");
        type = Objects.requireNonNull(type, "type");
        runId = CodeActValidation.requireNonBlank(runId, "runId");
        if (stepIndex < -1) {
            throw new IllegalArgumentException("stepIndex must be -1 or greater.");
        }
        stepId = CodeActValidation.optionalNonBlank(stepId, "stepId");
        data = Objects.requireNonNull(data, "data");
        if ((stepIndex == -1) != (stepId == null)) {
            throw new IllegalArgumentException("Run-level events must not identify a step.");
        }
    }
}
