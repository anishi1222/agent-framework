// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the immutable result for one evaluated item.
 *
 * @param itemId non-blank item identifier
 * @param status terminal item status
 * @param scores ordered evaluator scores
 * @param inputText evaluated query text
 * @param outputText evaluated response text
 * @param errorCode optional non-blank error code
 * @param errorMessage optional non-blank error detail
 * @param metadata immutable framework-owned result metadata
 */
public record EvalItemResult(
        String itemId,
        EvalItemStatus status,
        List<EvalScoreResult> scores,
        String inputText,
        String outputText,
        String errorCode,
        String errorMessage,
        Map<String, StateValue> metadata) {
    /** Creates a validated item result. */
    public EvalItemResult {
        itemId = EvaluationValidation.requireNonBlank(itemId, "itemId");
        Objects.requireNonNull(status, "status");
        scores = EvaluationValidation.copyList(scores, "scores");
        Objects.requireNonNull(inputText, "inputText");
        Objects.requireNonNull(outputText, "outputText");
        errorCode = EvaluationValidation.optionalNonBlank(errorCode, "errorCode");
        errorMessage = EvaluationValidation.optionalNonBlank(errorMessage, "errorMessage");
        metadata = EvaluationValidation.copyMap(metadata, "metadata");
        if (status == EvalItemStatus.ERROR && errorMessage == null) {
            throw new IllegalArgumentException("errorMessage is required for an error result.");
        }
        if (status != EvalItemStatus.ERROR && (errorCode != null || errorMessage != null)) {
            throw new IllegalArgumentException("Only an error result may contain error details.");
        }
        if (status == EvalItemStatus.PASS
                && (scores.isEmpty() || scores.stream().anyMatch(score -> !Boolean.TRUE.equals(score.passed())))) {
            throw new IllegalArgumentException("A passing item requires one or more passing scores.");
        }
    }

    /**
     * Reports whether the item passed.
     *
     * @return {@code true} for {@link EvalItemStatus#PASS}
     */
    public boolean isPassed() {
        return status == EvalItemStatus.PASS;
    }

    /**
     * Reports whether the item failed a quality check.
     *
     * @return {@code true} for {@link EvalItemStatus#FAIL}
     */
    public boolean isFailed() {
        return status == EvalItemStatus.FAIL;
    }

    /**
     * Reports whether evaluation of the item errored.
     *
     * @return {@code true} for {@link EvalItemStatus#ERROR}
     */
    public boolean isError() {
        return status == EvalItemStatus.ERROR;
    }
}
