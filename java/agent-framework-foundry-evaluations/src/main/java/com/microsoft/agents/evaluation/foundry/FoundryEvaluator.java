// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.time.Instant;
import java.util.Map;

/**
 * Represents one preview Foundry evaluator version.
 *
 * @param id asset identifier
 * @param name evaluator name
 * @param version evaluator version
 * @param type evaluator type
 * @param displayName optional display name
 * @param description optional description
 * @param metadata immutable metadata
 * @param createdAt optional creation time
 * @param preview always true for this SDK surface
 */
public record FoundryEvaluator(
        String id,
        String name,
        String version,
        String type,
        String displayName,
        String description,
        Map<String, String> metadata,
        Instant createdAt,
        boolean preview) {
    /** Creates and validates an evaluator. */
    public FoundryEvaluator {
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Evaluator name and version must not be blank.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (!preview) {
            throw new IllegalArgumentException("azure-ai-projects 2.3.0 evaluator management is preview.");
        }
    }
}
