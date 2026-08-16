// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.util.Map;

/**
 * Represents a connection, deployment, or index needed for evaluation discovery.
 *
 * @param kind resource family
 * @param id optional asset identifier
 * @param name resource name
 * @param version optional version
 * @param type optional provider type
 * @param description optional description
 * @param metadata immutable non-secret metadata
 */
public record FoundryProjectResource(
        String kind,
        String id,
        String name,
        String version,
        String type,
        String description,
        Map<String, String> metadata) {
    /** Creates and validates a project resource. */
    public FoundryProjectResource {
        if (kind == null || kind.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("kind and name must not be blank.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
