// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;

/**
 * Carries provider-neutral JSON-shaped metadata as an ordered content item.
 *
 * @param values immutable metadata values
 */
public record MetadataContent(Map<String, StateValue> values) implements Content {
    /** Creates immutable metadata content. */
    public MetadataContent {
        values = CoreValidation.copyStateMap(values, "values");
    }

    @Override
    public String kind() {
        return "metadata";
    }

    @Override
    public Map<String, StateValue> metadata() {
        return Map.of();
    }
}
