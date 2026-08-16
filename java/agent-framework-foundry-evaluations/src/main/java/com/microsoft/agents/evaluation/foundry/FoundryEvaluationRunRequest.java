// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Defines a run of an existing evaluation.
 *
 * @param evaluationId evaluation identifier
 * @param name optional run name
 * @param dataSource run data source
 * @param metadata immutable metadata
 */
public record FoundryEvaluationRunRequest(
        String evaluationId, String name, StateValue.ObjectValue dataSource, Map<String, String> metadata) {
    /** Creates and validates an evaluation run request. */
    public FoundryEvaluationRunRequest {
        if (evaluationId == null || evaluationId.isBlank()) {
            throw new IllegalArgumentException("evaluationId must not be blank.");
        }
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
