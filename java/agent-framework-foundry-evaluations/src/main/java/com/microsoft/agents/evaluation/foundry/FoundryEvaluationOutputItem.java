// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.List;

/**
 * Represents one evaluation output item.
 *
 * @param id output item identifier
 * @param status service status
 * @param results immutable evaluator results
 * @param sample optional provider sample
 * @param createdAt optional creation time
 */
public record FoundryEvaluationOutputItem(
        String id,
        String status,
        List<StateValue.ObjectValue> results,
        StateValue.ObjectValue sample,
        Instant createdAt) {
    /** Creates and defensively copies an output item. */
    public FoundryEvaluationOutputItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank.");
        }
        results = results == null ? List.of() : List.copyOf(results);
    }
}
