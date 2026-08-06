// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects how a sequential pipeline handles a participant failure. */
public enum SequentialFailurePolicy {
    /** Stops immediately and marks every remaining participant as skipped. */
    STOP,

    /** Records the failure and continues with the last successful conversation state. */
    CONTINUE
}
