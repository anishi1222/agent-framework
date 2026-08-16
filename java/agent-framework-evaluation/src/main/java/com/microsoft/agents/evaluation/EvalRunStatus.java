// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

/**
 * Identifies the terminal state of an evaluation run.
 */
public enum EvalRunStatus {
    /** Evaluation completed and produced counts. */
    COMPLETED,

    /** Evaluation failed before producing a complete result. */
    FAILED,

    /** Evaluation was cancelled. */
    CANCELLED,

    /** Evaluation timed out. */
    TIMEOUT
}
