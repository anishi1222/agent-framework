// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies one published feature-usage registry entry.
 *
 * @param value bit index in the inclusive range {@code 0..127}
 * @param id stable lowercase registry identifier
 */
public record FeatureUsageIndex(int value, String id) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(?:[._][a-z0-9]+)*");

    /** Creates a validated index descriptor. */
    public FeatureUsageIndex {
        if (value < 0 || value >= FeatureUsageRegistry.WIDTH) {
            throw new IllegalArgumentException("Feature index must be in range 0..127, got " + value + ".");
        }
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Feature id must be a stable lowercase registry identifier.");
        }
    }
}
