// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

/**
 * Represents immutable pass, fail, and error counts.
 *
 * @param passed number of passing items
 * @param failed number of failing items
 * @param errored number of errored items
 */
public record EvalCounts(int passed, int failed, int errored) {
    /** Creates validated non-negative counts. */
    public EvalCounts {
        if (passed < 0 || failed < 0 || errored < 0) {
            throw new IllegalArgumentException("Evaluation counts must not be negative.");
        }
    }

    /**
     * Returns the total number of counted items.
     *
     * @return total count
     */
    public int total() {
        return Math.addExact(Math.addExact(passed, failed), errored);
    }
}
