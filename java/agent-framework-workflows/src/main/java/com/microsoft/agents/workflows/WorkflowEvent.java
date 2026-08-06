// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Represents one deterministically sequenced workflow lifecycle event.
 *
 * @param sequence zero-based sequence within the run
 * @param type event type
 * @param runId logical run identifier
 * @param nodeId optional node identifier
 * @param superstep zero-based superstep, or {@code -1} for run-level events
 * @param correlationId optional node invocation correlation identifier
 * @param data immutable JSON-shaped event data
 */
public record WorkflowEvent(
        long sequence,
        WorkflowEventType type,
        String runId,
        NodeId nodeId,
        int superstep,
        String correlationId,
        StateValue data) {
    /** Creates a validated immutable workflow event. */
    public WorkflowEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative.");
        }
        Objects.requireNonNull(type, "type");
        runId = WorkflowValidation.requireNonBlank(runId, "runId");
        if (superstep < -1) {
            throw new IllegalArgumentException("superstep must be -1 or greater.");
        }
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank when present.");
        }
        data = data == null ? StateValue.nullValue() : data;
    }
}
