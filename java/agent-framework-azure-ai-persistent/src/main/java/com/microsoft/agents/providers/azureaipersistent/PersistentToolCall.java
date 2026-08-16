// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/**
 * Describes a tool call required to continue a run.
 *
 * @param id tool-call identifier
 * @param type provider tool type
 * @param name optional function name
 * @param argumentsJson optional function arguments JSON
 * @param supported whether the adapter can submit an output for this call
 */
public record PersistentToolCall(String id, String type, String name, String argumentsJson, boolean supported) {
    /** Creates and validates a tool call. */
    public PersistentToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }
    }
}
