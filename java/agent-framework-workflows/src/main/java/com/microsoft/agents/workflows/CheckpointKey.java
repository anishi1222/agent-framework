// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/**
 * Identifies the latest checkpoint snapshot for one logical workflow run.
 *
 * @param value stable non-blank checkpoint key
 */
public record CheckpointKey(String value) {
    /** Creates a validated checkpoint key. */
    public CheckpointKey {
        value = WorkflowValidation.requireNonBlank(value, "checkpoint key");
    }

    @Override
    public String toString() {
        return value;
    }
}
