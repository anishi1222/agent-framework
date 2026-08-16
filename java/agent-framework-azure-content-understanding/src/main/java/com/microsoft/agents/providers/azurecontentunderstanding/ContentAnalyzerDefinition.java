// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.Map;

/**
 * Represents one analyzer resource.
 *
 * @param analyzerId analyzer identifier
 * @param status expandable resource status
 * @param description optional description
 * @param baseAnalyzerId optional base analyzer
 * @param definition complete framework-owned analyzer representation
 * @param tags immutable tags
 * @param createdAt optional creation time
 * @param modifiedAt optional modification time
 */
public record ContentAnalyzerDefinition(
        String analyzerId,
        String status,
        String description,
        String baseAnalyzerId,
        StateValue.ObjectValue definition,
        Map<String, String> tags,
        Instant createdAt,
        Instant modifiedAt) {
    /** Creates and defensively copies an analyzer. */
    public ContentAnalyzerDefinition {
        if (analyzerId == null || analyzerId.isBlank()) {
            throw new IllegalArgumentException("analyzerId must not be blank.");
        }
        definition = java.util.Objects.requireNonNull(definition, "definition");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
