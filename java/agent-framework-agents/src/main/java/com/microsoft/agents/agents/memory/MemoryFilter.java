// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Describes immutable equality predicates over memory metadata.
 *
 * <p>Tenant and scope are not filter fields. They are mandatory routing values on {@link
 * MemoryScope} and cannot be overridden by this filter.
 *
 * @param equals metadata keys and scalar values that must match
 */
public record MemoryFilter(Map<String, StateValue> equals) {
    private static final MemoryFilter NONE = new MemoryFilter(Map.of());

    /** Creates a validated immutable filter. */
    public MemoryFilter {
        MemoryValidation.requireNonNull(equals, "equals");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        equals.forEach((key, value) -> {
            String safeKey = MemoryValidation.requireNonBlank(key, "filter key");
            StateValue safeValue = MemoryValidation.requireNonNull(value, "filter value");
            if (safeValue instanceof StateValue.ArrayValue || safeValue instanceof StateValue.ObjectValue) {
                throw new ValidationException("Memory filter values must be JSON scalars.");
            }
            copy.put(safeKey, safeValue);
        });
        equals = Map.copyOf(copy);
    }

    /**
     * Returns a filter with no metadata predicates.
     *
     * @return empty filter
     */
    public static MemoryFilter none() {
        return NONE;
    }
}
