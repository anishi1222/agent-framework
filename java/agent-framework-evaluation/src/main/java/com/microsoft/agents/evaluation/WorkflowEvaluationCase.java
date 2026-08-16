// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import java.util.Objects;

/**
 * Associates one workflow input with its immutable evaluation case.
 *
 * @param <I> workflow input type
 * @param input workflow input
 * @param evaluationCase query messages and expected evaluation data
 */
public record WorkflowEvaluationCase<I>(I input, EvaluationCase evaluationCase) {
    /** Creates a validated workflow evaluation case. */
    public WorkflowEvaluationCase {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(evaluationCase, "evaluationCase");
    }

    /**
     * Creates a workflow case with one text query.
     *
     * @param input workflow input
     * @param query non-blank user query
     * @param <I> workflow input type
     * @return immutable workflow evaluation case
     */
    public static <I> WorkflowEvaluationCase<I> text(I input, String query) {
        return new WorkflowEvaluationCase<>(input, EvaluationCase.text(query));
    }
}
