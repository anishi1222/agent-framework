// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.time.Duration;

/**
 * Carries privacy-safe Purview operation telemetry without prompts, responses, identities, or
 * credentials.
 *
 * @param operation operation name
 * @param statusCode optional HTTP status
 * @param succeeded whether the operation succeeded
 * @param duration operation duration
 * @param requestId optional service request identifier
 */
public record PurviewTelemetryEvent(
        String operation, Integer statusCode, boolean succeeded, Duration duration, String requestId) {
    /** Creates and validates a telemetry event. */
    public PurviewTelemetryEvent {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank.");
        }
        duration = java.util.Objects.requireNonNull(duration, "duration");
    }
}
