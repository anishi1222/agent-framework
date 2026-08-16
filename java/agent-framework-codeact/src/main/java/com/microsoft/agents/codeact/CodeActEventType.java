// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

/** Identifies a stable CodeAct lifecycle event kind. */
public enum CodeActEventType {
    /** A bounded logical run was created. */
    RUN_STARTED,

    /** Caller or workspace policy rejected the program before process execution. */
    POLICY_REJECTED,

    /** The exact program was presented for bundled approval. */
    APPROVAL_REQUESTED,

    /** The bundled approval request was approved. */
    APPROVAL_GRANTED,

    /** The bundled approval request was rejected. */
    APPROVAL_DENIED,

    /** A bounded program step started. */
    STEP_STARTED,

    /** A bounded program step completed. */
    STEP_COMPLETED,

    /** A configured step or output limit was reached. */
    LIMIT_REACHED,

    /** The program completed successfully. */
    RUN_COMPLETED,

    /** The program ended with a deterministic non-success status. */
    RUN_TERMINATED,

    /** Caller cancellation won the run terminal race. */
    RUN_CANCELLED
}
