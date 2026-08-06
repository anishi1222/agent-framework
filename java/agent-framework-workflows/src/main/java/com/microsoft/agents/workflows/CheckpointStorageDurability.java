// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Describes the durability boundary acknowledged by checkpoint storage completion. */
public enum CheckpointStorageDurability {
    /** Completion means only that process memory was updated. */
    PROCESS_MEMORY,
    /** Completion means the adapter's documented durable backend committed. */
    DURABLE_BACKEND
}
