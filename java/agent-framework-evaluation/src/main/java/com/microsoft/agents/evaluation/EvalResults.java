// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents immutable results from one provider-neutral evaluation run.
 *
 * @param provider non-blank evaluator or provider name
 * @param evaluationName non-blank display name
 * @param evaluationId optional provider evaluation identifier
 * @param runId optional provider run identifier
 * @param status terminal run status
 * @param counts aggregate counts
 * @param perEvaluator immutable per-evaluator counts
 * @param items ordered item results
 * @param subResults immutable nested workflow or participant results
 * @param error optional non-blank run error detail
 */
public record EvalResults(
        String provider,
        String evaluationName,
        String evaluationId,
        String runId,
        EvalRunStatus status,
        EvalCounts counts,
        Map<String, EvalCounts> perEvaluator,
        List<EvalItemResult> items,
        Map<String, EvalResults> subResults,
        String error) {
    /** Creates validated immutable evaluation results. */
    public EvalResults {
        provider = EvaluationValidation.requireNonBlank(provider, "provider");
        evaluationName = EvaluationValidation.requireNonBlank(evaluationName, "evaluationName");
        evaluationId = EvaluationValidation.optionalNonBlank(evaluationId, "evaluationId");
        runId = EvaluationValidation.optionalNonBlank(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(counts, "counts");
        perEvaluator = EvaluationValidation.copyMap(perEvaluator, "perEvaluator");
        items = EvaluationValidation.copyList(items, "items");
        subResults = EvaluationValidation.copyMap(subResults, "subResults");
        error = EvaluationValidation.optionalNonBlank(error, "error");
        if (counts.total() < items.size()) {
            throw new IllegalArgumentException("counts must cover every supplied item result.");
        }
    }

    /**
     * Reports whether the run and every nested result passed.
     *
     * @return {@code true} only for a completed, non-empty, failure-free result tree
     */
    public boolean allPassed() {
        if (status != EvalRunStatus.COMPLETED) {
            return false;
        }
        boolean hasOwnEvidence = counts.total() > 0;
        boolean ownPassed = counts.failed() == 0 && counts.errored() == 0;
        boolean childrenPassed = subResults.values().stream().allMatch(EvalResults::allPassed);
        return ownPassed && childrenPassed && (hasOwnEvidence || !subResults.isEmpty());
    }

    /**
     * Throws when the result tree contains failures, errors, or no passing evidence.
     */
    public void assertPassed() {
        if (!allPassed()) {
            throw new EvalNotPassedException("Evaluation '" + evaluationName + "' did not pass: " + counts.passed()
                    + " passed, " + counts.failed() + " failed, " + counts.errored() + " errored.");
        }
    }
}
