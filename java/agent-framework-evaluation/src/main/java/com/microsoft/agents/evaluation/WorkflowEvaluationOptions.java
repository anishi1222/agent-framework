// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.workflows.WorkflowRunOptions;
import java.util.Objects;

/**
 * Configures workflow evaluation execution.
 *
 * @param evaluationName non-blank display name
 * @param repetitions number of independent executions per case
 * @param runOptions immutable workflow run options
 */
public record WorkflowEvaluationOptions(String evaluationName, int repetitions, WorkflowRunOptions runOptions) {
    /** Creates validated workflow evaluation options. */
    public WorkflowEvaluationOptions {
        evaluationName = EvaluationValidation.requireNonBlank(evaluationName, "evaluationName");
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be at least one.");
        }
        Objects.requireNonNull(runOptions, "runOptions");
    }

    /**
     * Returns default workflow evaluation options.
     *
     * @return one repetition with default workflow run options
     */
    public static WorkflowEvaluationOptions defaults() {
        return new WorkflowEvaluationOptions("Workflow Evaluation", 1, WorkflowRunOptions.defaults());
    }
}
