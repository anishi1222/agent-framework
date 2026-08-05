// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Carries usage accounting in an ordered update content stream.
 *
 * @param usage usage details
 * @param metadata immutable additive metadata
 */
public record UsageContent(UsageDetails usage, Map<String, StateValue> metadata) implements Content {
    /** Creates validated usage content. */
    public UsageContent {
        Objects.requireNonNull(usage, "usage");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates usage content without metadata.
     *
     * @param usage usage details
     */
    public UsageContent(UsageDetails usage) {
        this(usage, Map.of());
    }

    @Override
    public String kind() {
        return "usage";
    }
}
