// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Classifies a terminal tool invocation result.
 */
public enum ToolInvocationOutcome {
    /** The function completed successfully. */
    SUCCEEDED,
    /** Argument binding or function execution produced a correlated error result. */
    FAILED,
    /** Run cancellation prevented or interrupted further framework work. */
    CANCELLED,
    /** An approval decision rejected execution. */
    REJECTED,
    /** A duplicate observation reused the owning invocation result. */
    DUPLICATE
}
