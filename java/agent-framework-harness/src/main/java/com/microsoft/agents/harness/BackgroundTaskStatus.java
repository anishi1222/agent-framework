// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/** Classifies the persisted lifecycle of one background-agent task. */
public enum BackgroundTaskStatus {
    /** The task has an in-process execution handle. */
    RUNNING,
    /** The task completed successfully. */
    COMPLETED,
    /** The task completed with a failure. */
    FAILED,
    /** The task was cancelled while its process-local child session remained available. */
    CANCELLED,
    /** Persisted running state was restored without its process-local execution handle. */
    LOST
}
