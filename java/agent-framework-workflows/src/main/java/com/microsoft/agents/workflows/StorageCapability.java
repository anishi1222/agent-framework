// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Identifies an optional checkpoint-storage transaction capability. */
public enum StorageCapability {
    /** Checkpoint compare-and-set and invocation-ledger mutations commit atomically. */
    ATOMIC_CHECKPOINT_AND_LEDGER
}
