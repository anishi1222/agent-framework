// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

/** Controls explicit bounded fallback when server search capability is unavailable. */
public enum CosmosMemoryFallback {
    /** Search capability failures propagate without fallback. */
    DISABLED,
    /** A bounded single-partition scan ranks at most the configured document limit locally. */
    BOUNDED_PARTITION_SCAN
}
