// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

/** Selects the provider-neutral memory retrieval strategy. */
public enum MemorySearchMode {
    /** Uses a provider's full-text capability. */
    FULL_TEXT,
    /** Uses vector similarity only. */
    VECTOR,
    /** Combines full-text and vector ranking. */
    HYBRID
}
