// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines a persistent agent to create.
 *
 * @param model model deployment
 * @param name optional name
 * @param description optional description
 * @param instructions optional system instructions
 * @param tools immutable tools
 * @param metadata immutable service metadata
 */
public record PersistentAgentCreateRequest(
        String model,
        String name,
        String description,
        String instructions,
        List<PersistentAgentTool> tools,
        Map<String, String> metadata) {
    /** Creates and validates a request. */
    public PersistentAgentCreateRequest {
        model = requireNonBlank(model, "model");
        name = optionalNonBlank(name, "name");
        description = optionalNonBlank(description, "description");
        instructions = optionalNonBlank(instructions, "instructions");
        tools = tools == null ? List.of() : List.copyOf(tools);
        tools.forEach(tool -> Objects.requireNonNull(tool, "tool"));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
