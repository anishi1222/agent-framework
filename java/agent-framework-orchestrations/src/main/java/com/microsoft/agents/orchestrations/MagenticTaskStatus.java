// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Identifies one Magentic task's immutable ledger status. */
public enum MagenticTaskStatus {
    /** The task has not started. */
    PENDING,

    /** The task is assigned and in progress. */
    IN_PROGRESS,

    /** The task completed. */
    COMPLETED,

    /** The assigned participant failed the task. */
    FAILED
}
