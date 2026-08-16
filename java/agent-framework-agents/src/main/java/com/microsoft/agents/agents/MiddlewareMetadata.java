// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns metadata shared only within one middleware invocation pipeline.
 *
 * <p>Snapshots are immutable and detached. A new instance is created for every run or chat/function
 * invocation, preventing cross-run leakage when middleware instances are shared.
 */
public final class MiddlewareMetadata {
    private final ConcurrentHashMap<String, StateValue> values;

    /** Creates empty isolated metadata. */
    public MiddlewareMetadata() {
        this(Map.of());
    }

    /**
     * Creates isolated metadata initialized from immutable values.
     *
     * @param initialValues initial metadata
     */
    public MiddlewareMetadata(Map<String, StateValue> initialValues) {
        values = new ConcurrentHashMap<>(AgentValidation.copyMetadata(initialValues));
    }

    /**
     * Returns a value when present.
     *
     * @param key metadata key
     * @return optional value
     */
    public Optional<StateValue> get(String key) {
        return Optional.ofNullable(values.get(AgentValidation.requireNonBlank(key, "key")));
    }

    /**
     * Associates a value.
     *
     * @param key metadata key
     * @param value immutable state value
     * @return prior value, or {@code null}
     */
    public StateValue put(String key, StateValue value) {
        return values.put(AgentValidation.requireNonBlank(key, "key"), AgentValidation.requireNonNull(value, "value"));
    }

    /**
     * Removes a value.
     *
     * @param key metadata key
     * @return prior value, or {@code null}
     */
    public StateValue remove(String key) {
        return values.remove(AgentValidation.requireNonBlank(key, "key"));
    }

    /**
     * Returns an immutable detached snapshot.
     *
     * @return current metadata
     */
    public Map<String, StateValue> snapshot() {
        return Map.copyOf(values);
    }
}
