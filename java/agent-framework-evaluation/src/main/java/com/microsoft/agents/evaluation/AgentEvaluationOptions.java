// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.RunOptions;
import java.util.Objects;

/**
 * Configures agent evaluation execution.
 *
 * @param evaluationName non-blank display name
 * @param repetitions number of independent executions per case
 * @param runOptions immutable agent run options
 */
public record AgentEvaluationOptions(String evaluationName, int repetitions, RunOptions runOptions) {
    /** Creates validated agent evaluation options. */
    public AgentEvaluationOptions {
        evaluationName = EvaluationValidation.requireNonBlank(evaluationName, "evaluationName");
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be at least one.");
        }
        Objects.requireNonNull(runOptions, "runOptions");
    }

    /**
     * Returns default agent evaluation options.
     *
     * @return one repetition with empty run options
     */
    public static AgentEvaluationOptions defaults() {
        return new AgentEvaluationOptions("Agent Evaluation", 1, RunOptions.empty());
    }
}
