// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/**
 * Supplies one caller-reviewed tool output.
 *
 * @param toolCallId required tool-call identifier
 * @param output output text or JSON
 */
public record PersistentToolOutput(String toolCallId, String output) {
    /** Creates and validates a tool output. */
    public PersistentToolOutput {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank.");
        }
        if (output == null) {
            throw new NullPointerException("output");
        }
    }
}
