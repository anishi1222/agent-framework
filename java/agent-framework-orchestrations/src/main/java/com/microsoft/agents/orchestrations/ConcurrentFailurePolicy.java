// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects how a concurrent orchestration handles participant failures. */
public enum ConcurrentFailurePolicy {
    /** Cancels unfinished siblings after the first observed participant failure. */
    FAIL_FAST,

    /** Lets every participant finish and returns an explicit failed result containing all errors. */
    COLLECT_ERRORS
}
