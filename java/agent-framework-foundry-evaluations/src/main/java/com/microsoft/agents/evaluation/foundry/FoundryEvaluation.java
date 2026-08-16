// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.time.Instant;
import java.util.Map;

/**
 * Represents an evaluation definition.
 *
 * @param id evaluation identifier
 * @param name optional name
 * @param createdAt optional creation time
 * @param metadata immutable metadata
 */
public record FoundryEvaluation(String id, String name, Instant createdAt, Map<String, String> metadata) {
    /** Creates and validates an evaluation. */
    public FoundryEvaluation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
