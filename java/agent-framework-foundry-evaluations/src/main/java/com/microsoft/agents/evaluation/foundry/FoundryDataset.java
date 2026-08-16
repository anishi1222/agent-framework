// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import java.util.Map;

/**
 * Represents one Foundry dataset version.
 *
 * @param id asset identifier
 * @param name dataset name
 * @param version dataset version
 * @param type dataset type
 * @param description optional description
 * @param tags immutable tags
 */
public record FoundryDataset(
        String id, String name, String version, String type, String description, Map<String, String> tags) {
    /** Creates and defensively copies a dataset. */
    public FoundryDataset {
        require(name, "name");
        require(version, "version");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
