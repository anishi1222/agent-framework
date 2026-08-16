// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

/**
 * Indicates that an evaluation result did not satisfy its pass gate.
 */
public final class EvalNotPassedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an evaluation failure.
     *
     * @param message failure detail
     */
    public EvalNotPassedException(String message) {
        super(EvaluationValidation.requireNonBlank(message, "message"));
    }
}
