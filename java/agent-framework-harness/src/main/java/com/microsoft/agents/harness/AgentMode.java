// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/**
 * Describes one named harness operating mode.
 *
 * @param name case-insensitive non-blank mode name
 * @param instructions non-blank mode guidance
 */
public record AgentMode(String name, String instructions) {
    /** Creates a validated immutable mode. */
    public AgentMode {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank.");
        }
    }
}
