// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents one deterministically sequenced hosted stream event.
 *
 * @param sequence zero-based hosting sequence
 * @param type event type
 * @param runId active-run identifier
 * @param createdAt creation instant
 * @param data redacted immutable event data
 */
public record HostingEvent(long sequence, HostingEventType type, String runId, Instant createdAt, StateValue data) {
    /** Creates a validated immutable event. */
    public HostingEvent {
        if (sequence < 0) {
            throw new com.microsoft.agents.core.ValidationException("sequence must not be negative.");
        }
        Objects.requireNonNull(type, "type");
        runId = HostingValidation.nonBlank(runId, "runId");
        Objects.requireNonNull(createdAt, "createdAt");
        data = HostingRedactor.redact(Objects.requireNonNull(data, "data"));
    }
}
