// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents one immutable deterministically sequenced orchestration event.
 *
 * @param sequence zero-based sequence within the run
 * @param eventId stable event identifier derived from the run and sequence
 * @param type event type
 * @param orchestrationId stable orchestration identifier
 * @param runId logical run identifier
 * @param participantId optional participant identifier
 * @param turn zero-based pattern turn, or {@code -1} for run-level events
 * @param correlationId optional invocation correlation identifier
 * @param data immutable JSON-shaped event data
 */
public record OrchestrationEvent(
        long sequence,
        String eventId,
        OrchestrationEventType type,
        String orchestrationId,
        String runId,
        String participantId,
        int turn,
        String correlationId,
        StateValue data) {
    /** Creates a validated immutable event. */
    public OrchestrationEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative.");
        }
        eventId = OrchestrationValidation.requireId(eventId, "eventId");
        type = Objects.requireNonNull(type, "type");
        orchestrationId = OrchestrationValidation.requireId(orchestrationId, "orchestrationId");
        runId = OrchestrationValidation.requireId(runId, "runId");
        participantId = OrchestrationValidation.optionalId(participantId, "participantId");
        if (turn < -1) {
            throw new IllegalArgumentException("turn must be -1 or greater.");
        }
        correlationId = OrchestrationValidation.optionalId(correlationId, "correlationId");
        data = data == null ? StateValue.nullValue() : data;
    }
}
