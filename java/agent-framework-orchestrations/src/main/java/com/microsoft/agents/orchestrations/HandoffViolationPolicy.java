// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Selects how invalid handoff requests affect the run. */
public enum HandoffViolationPolicy {
    /** Produces an explicit failed orchestration result. */
    FAIL,

    /** Produces an explicit terminated orchestration result with the latest response. */
    TERMINATE,

    /** Ignores the invalid request and lets the current participant take another bounded turn. */
    IGNORE
}
