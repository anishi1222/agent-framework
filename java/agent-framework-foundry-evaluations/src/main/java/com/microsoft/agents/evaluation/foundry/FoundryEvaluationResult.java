// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.util.List;

/**
 * Combines a terminal evaluation run with all fetched output items.
 *
 * @param run terminal run
 * @param outputItems immutable output items
 */
public record FoundryEvaluationResult(FoundryEvaluationRun run, List<FoundryEvaluationOutputItem> outputItems) {
    /** Creates and defensively copies a result. */
    public FoundryEvaluationResult {
        run = java.util.Objects.requireNonNull(run, "run");
        outputItems = outputItems == null ? List.of() : List.copyOf(outputItems);
    }
}
