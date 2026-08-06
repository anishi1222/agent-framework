// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Classifies the explicit domain outcome of an orchestration run. */
public enum OrchestrationOutcome {
    /** The orchestration produced its requested terminal output. */
    COMPLETED,

    /** The orchestration completed but retained one or more non-fatal participant errors. */
    COMPLETED_WITH_ERRORS,

    /** The orchestration stopped at a configured termination boundary. */
    TERMINATED,

    /** The orchestration requires approval or human input before it can continue. */
    INPUT_REQUIRED,

    /** The orchestration could not produce a complete answer. */
    UNSOLVED,

    /** The orchestration reached an explicit failed domain result. */
    FAILED
}
