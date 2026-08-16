// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Contains immutable framework-owned metadata for one memory record.
 *
 * @param values JSON-shaped metadata values
 */
public record MemoryMetadata(Map<String, StateValue> values) {
    private static final MemoryMetadata EMPTY = new MemoryMetadata(Map.of());

    /** Creates validated immutable metadata. */
    public MemoryMetadata {
        MemoryValidation.requireNonNull(values, "values");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                MemoryValidation.requireNonBlank(key, "metadata key"),
                MemoryValidation.requireNonNull(value, "metadata value")));
        values = Map.copyOf(copy);
    }

    /**
     * Returns shared empty metadata.
     *
     * @return empty metadata
     */
    public static MemoryMetadata empty() {
        return EMPTY;
    }
}
