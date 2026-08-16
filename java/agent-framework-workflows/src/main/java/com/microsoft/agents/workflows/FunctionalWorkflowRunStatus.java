// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Classifies the successful boundary reached by one functional workflow invocation. */
public enum FunctionalWorkflowRunStatus {
    /** The workflow returned normally. */
    COMPLETED,

    /** The workflow paused while waiting for external input. */
    INPUT_REQUIRED
}
