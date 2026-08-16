// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Contains one complete, deterministically ordered fan-in epoch. */
public final class FanInInput {
    private final long epoch;

    private final Map<NodeId, Object> values;

    FanInInput(long epoch, Map<NodeId, Object> values) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative.");
        }
        this.epoch = epoch;
        Objects.requireNonNull(values, "values");
        TreeMap<NodeId, Object> sorted = new TreeMap<>();
        values.forEach((key, value) ->
                sorted.put(Objects.requireNonNull(key, "source id"), Objects.requireNonNull(value, "source value")));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * Returns the zero-based fan-in epoch.
     *
     * @return fan-in epoch
     */
    public long epoch() {
        return epoch;
    }

    /**
     * Returns the ordered source identifiers.
     *
     * @return source identifiers in lexical order
     */
    public List<NodeId> sourceIds() {
        return List.copyOf(values.keySet());
    }

    /**
     * Reads one source value with a runtime type check.
     *
     * @param sourceId source node identifier
     * @param valueType expected value type
     * @param <T> value type
     * @return typed source value
     */
    public <T> T value(NodeId sourceId, Class<T> valueType) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(valueType, "valueType");
        Object value = values.get(sourceId);
        if (value == null) {
            throw new WorkflowValidationException("Fan-in input has no value from source '" + sourceId + "'.");
        }
        return valueType.cast(value);
    }

    /**
     * Returns all source values after checking one common type.
     *
     * @param valueType expected value type
     * @param <T> value type
     * @return immutable values in source identifier order
     */
    public <T> List<T> values(Class<T> valueType) {
        Objects.requireNonNull(valueType, "valueType");
        return values.values().stream().map(valueType::cast).toList();
    }

    Map<NodeId, Object> rawValues() {
        return values;
    }
}
