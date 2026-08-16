// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/** Selects explicit Purview dependency failure behavior. */
public enum PurviewFailureMode {
    /** Reject agent execution when policy evaluation cannot be completed. */
    FAIL_CLOSED,
    /** Continue agent execution when Purview is unavailable. */
    FAIL_OPEN
}
