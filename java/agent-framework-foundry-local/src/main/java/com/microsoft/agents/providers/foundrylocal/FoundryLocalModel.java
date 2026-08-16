// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

/**
 * Represents a Foundry Local catalog model without exposing native SDK types.
 *
 * @param name model identifier
 * @param alias optional model alias
 * @param displayName optional display name
 * @param providerType optional catalog provider
 * @param version optional version
 * @param modelType optional model format
 * @param task optional task classification
 * @param supportsToolCalling whether the catalog reports tool support
 * @param license optional model license
 */
public record FoundryLocalModel(
        String name,
        String alias,
        String displayName,
        String providerType,
        String version,
        String modelType,
        String task,
        boolean supportsToolCalling,
        String license) {}
