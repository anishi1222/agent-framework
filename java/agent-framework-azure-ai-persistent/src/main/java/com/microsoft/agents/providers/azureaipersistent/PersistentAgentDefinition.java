// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents immutable persistent-agent metadata.
 *
 * @param id service agent identifier
 * @param model model deployment
 * @param name optional name
 * @param description optional description
 * @param instructions optional instructions
 * @param tools immutable tool descriptions
 * @param metadata immutable service metadata
 * @param createdAt optional creation time
 */
public record PersistentAgentDefinition(
        String id,
        String model,
        String name,
        String description,
        String instructions,
        List<PersistentAgentTool> tools,
        Map<String, String> metadata,
        Instant createdAt) {
    /** Creates and defensively copies an agent definition. */
    public PersistentAgentDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank.");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
