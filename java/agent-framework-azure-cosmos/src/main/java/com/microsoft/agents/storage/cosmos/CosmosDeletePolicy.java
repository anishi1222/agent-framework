// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/** Selects revision-preserving tombstones or irreversible physical deletion. */
public enum CosmosDeletePolicy {
    /** Replaces the item with an expiring tombstone so revision history remains monotonic. */
    SOFT,
    /** Physically removes the item; recreation starts a new revision lineage. */
    HARD
}
