// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

/**
 * Identifies the terminal quality status of one evaluation item.
 */
public enum EvalItemStatus {
    /** Every executed evaluator passed. */
    PASS,

    /** At least one evaluator failed, or no evaluator supplied evidence. */
    FAIL,

    /** At least one evaluator could not produce a score. */
    ERROR
}
