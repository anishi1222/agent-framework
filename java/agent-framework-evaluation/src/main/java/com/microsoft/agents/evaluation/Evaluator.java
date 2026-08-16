// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Defines a provider-neutral asynchronous evaluation backend.
 */
public interface Evaluator {
    /**
     * Returns the stable non-blank evaluator name.
     *
     * @return evaluator name
     */
    String name();

    /**
     * Evaluates a non-empty immutable item batch asynchronously.
     *
     * @param items evaluation items
     * @param evaluationName non-blank evaluation display name
     * @param cancellation caller-owned cancellation signal
     * @return terminal evaluation-result stage
     */
    CompletionStage<EvalResults> evaluateAsync(
            List<EvalItem> items, String evaluationName, RunCancellation cancellation);

    /**
     * Evaluates items with an evaluator-owned cancellation signal.
     *
     * @param items evaluation items
     * @param evaluationName non-blank evaluation display name
     * @return terminal evaluation-result stage
     */
    default CompletionStage<EvalResults> evaluateAsync(List<EvalItem> items, String evaluationName) {
        return evaluateAsync(items, evaluationName, new DefaultRunCancellation());
    }
}
