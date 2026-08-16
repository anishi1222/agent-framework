// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/** Describes the acknowledgement boundary of a {@link SessionStore}. */
public enum SessionStoreDurability {
    /** Completion means the value is visible in the current process only. */
    PROCESS_MEMORY,
    /** Completion means the backend has acknowledged its durable commit boundary. */
    DURABLE_COMMIT
}
