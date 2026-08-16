// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Defines a Foundry evaluation.
 *
 * @param name optional display name
 * @param dataSourceConfig data source configuration object
 * @param testingCriteria non-empty evaluator criteria
 * @param metadata immutable metadata
 */
public record FoundryEvaluationRequest(
        String name,
        StateValue.ObjectValue dataSourceConfig,
        List<StateValue.ObjectValue> testingCriteria,
        Map<String, String> metadata) {
    /** Creates and validates an evaluation request. */
    public FoundryEvaluationRequest {
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        dataSourceConfig = java.util.Objects.requireNonNull(dataSourceConfig, "dataSourceConfig");
        testingCriteria = testingCriteria == null ? List.of() : List.copyOf(testingCriteria);
        if (testingCriteria.isEmpty()) {
            throw new IllegalArgumentException("testingCriteria must not be empty.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
