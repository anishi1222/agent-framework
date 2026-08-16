// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

/** Selects the explicit same-thread concurrent-run policy. */
public enum AGUIConcurrentRunPolicy {
    /** Reject a second run while the principal-scoped thread has an active run. */
    REJECT
}
