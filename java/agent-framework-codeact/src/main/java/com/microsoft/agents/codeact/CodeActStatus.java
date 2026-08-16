// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

/** Identifies the terminal state of a bounded CodeAct run. */
public enum CodeActStatus {
    /** Every program step completed successfully. */
    COMPLETED,

    /** Bundled execution approval was denied. */
    APPROVAL_DENIED,

    /** Caller or workspace policy denied the program. */
    POLICY_DENIED,

    /** The configured wall-clock bound was reached. */
    TIMED_OUT,

    /** The program contained more steps than the configured bound. */
    MAX_STEPS_REACHED,

    /** A step or framework integration failed. */
    FAILED,

    /** Caller cancellation won before a result could complete. */
    CANCELLED
}
