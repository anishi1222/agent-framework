// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Represents one immutable evaluator score for one item.
 *
 * @param name non-blank evaluator or check name
 * @param score finite numeric score
 * @param passed optional threshold result
 * @param reason optional non-blank explanation
 * @param metadata immutable framework-owned score metadata
 */
public record EvalScoreResult(
        String name, double score, Boolean passed, String reason, Map<String, StateValue> metadata) {
    /** Creates a validated score result. */
    public EvalScoreResult {
        name = EvaluationValidation.requireNonBlank(name, "name");
        score = EvaluationValidation.requireFinite(score, "score");
        reason = EvaluationValidation.optionalNonBlank(reason, "reason");
        metadata = EvaluationValidation.copyMap(metadata, "metadata");
    }

    /**
     * Creates a score without metadata.
     *
     * @param name non-blank evaluator or check name
     * @param score finite numeric score
     * @param passed optional threshold result
     * @param reason optional explanation
     */
    public EvalScoreResult(String name, double score, Boolean passed, String reason) {
        this(name, score, passed, reason, Map.of());
    }
}
