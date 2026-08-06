// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

/** Reports that no complete atomic group fits within a required compaction budget. */
public final class CompactionBudgetExceededException extends CompactionException {
    private static final long serialVersionUID = 1L;

    private final long budget;

    private final long required;

    /**
     * Creates an explicit budget overflow.
     *
     * @param budget configured positive budget
     * @param required estimated tokens required by the smallest eligible atomic group
     */
    public CompactionBudgetExceededException(long budget, long required) {
        super("No complete compaction group fits within token budget " + budget + "; requires " + required + ".");
        this.budget = budget;
        this.required = required;
    }

    /**
     * Returns the configured budget.
     *
     * @return positive budget
     */
    public long budget() {
        return budget;
    }

    /**
     * Returns the required estimate.
     *
     * @return required tokens
     */
    public long required() {
        return required;
    }
}
