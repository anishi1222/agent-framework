// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one deterministic hosted route.
 *
 * @param id stable route identifier
 * @param kind target kind
 * @param name optional display name
 * @param description optional description
 * @param streamingSupported whether streaming dispatch is available
 * @param resumeSupported whether a production continuation path is available
 * @param metadata immutable public metadata
 */
public record HostingRouteDescriptor(
        String id,
        HostingRouteKind kind,
        String name,
        String description,
        boolean streamingSupported,
        boolean resumeSupported,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable descriptor. */
    public HostingRouteDescriptor {
        id = HostingValidation.routeId(id);
        Objects.requireNonNull(kind, "kind");
        name = HostingValidation.optionalNonBlank(name, "name");
        description = HostingValidation.optionalNonBlank(description, "description");
        Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> copy.put(
                HostingValidation.nonBlank(key, "metadata key"), Objects.requireNonNull(value, "metadata value")));
        metadata = Map.copyOf(copy);
    }
}
