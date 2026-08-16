// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Represents one immutable local-check result.
 *
 * @param passed whether the check passed
 * @param score finite numeric score
 * @param reason non-blank human-readable explanation
 * @param metadata immutable framework-owned check metadata
 */
public record CheckResult(boolean passed, double score, String reason, Map<String, StateValue> metadata) {
    /** Creates a validated check result. */
    public CheckResult {
        score = EvaluationValidation.requireFinite(score, "score");
        reason = EvaluationValidation.requireNonBlank(reason, "reason");
        metadata = EvaluationValidation.copyMap(metadata, "metadata");
    }

    /**
     * Creates a passing Boolean check result.
     *
     * @param reason non-blank explanation
     * @return passing result with score {@code 1.0}
     */
    public static CheckResult pass(String reason) {
        return new CheckResult(true, 1.0, reason, Map.of());
    }

    /**
     * Creates a failing Boolean check result.
     *
     * @param reason non-blank explanation
     * @return failing result with score {@code 0.0}
     */
    public static CheckResult fail(String reason) {
        return new CheckResult(false, 0.0, reason, Map.of());
    }

    /**
     * Creates a scored check result.
     *
     * @param score finite score
     * @param threshold finite inclusive pass threshold
     * @param reason non-blank explanation
     * @return scored result
     */
    public static CheckResult scored(double score, double threshold, String reason) {
        double checkedScore = EvaluationValidation.requireFinite(score, "score");
        double checkedThreshold = EvaluationValidation.requireFinite(threshold, "threshold");
        return new CheckResult(checkedScore >= checkedThreshold, checkedScore, reason, Map.of());
    }
}
