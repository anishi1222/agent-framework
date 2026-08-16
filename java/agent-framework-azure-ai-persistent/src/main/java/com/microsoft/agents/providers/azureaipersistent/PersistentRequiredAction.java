// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;

/**
 * Represents a continuation required by a persistent run.
 *
 * @param type service action type
 * @param toolCalls immutable required tool calls
 * @param supported whether the adapter can continue this action
 */
public record PersistentRequiredAction(String type, List<PersistentToolCall> toolCalls, boolean supported) {
    /** Creates and validates a required action. */
    public PersistentRequiredAction {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
