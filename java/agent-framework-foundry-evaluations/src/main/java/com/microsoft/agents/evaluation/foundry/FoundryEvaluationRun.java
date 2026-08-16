// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.net.URI;
import java.time.Instant;

/**
 * Represents one cloud evaluation run.
 *
 * @param id run identifier
 * @param evaluationId evaluation identifier
 * @param status expandable status
 * @param reportUri optional Foundry report URI
 * @param errorCode optional error code
 * @param errorMessage optional sanitized error message
 * @param createdAt optional creation time
 */
public record FoundryEvaluationRun(
        String id,
        String evaluationId,
        FoundryEvaluationStatus status,
        URI reportUri,
        String errorCode,
        String errorMessage,
        Instant createdAt) {
    /** Creates and validates a run. */
    public FoundryEvaluationRun {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (evaluationId == null || evaluationId.isBlank()) {
            throw new IllegalArgumentException("evaluationId must not be blank.");
        }
        status = java.util.Objects.requireNonNull(status, "status");
    }
}
