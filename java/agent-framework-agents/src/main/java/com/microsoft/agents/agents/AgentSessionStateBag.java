// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provides an immutable detached view of JSON-shaped session state.
 *
 * <p>Mutation is deliberately absent from this type. Callers update state through the thread-safe
 * operations on {@link AgentSession}, then request another state view or snapshot.
 */
public final class AgentSessionStateBag {
    private static final AgentSessionStateBag EMPTY = new AgentSessionStateBag(Map.of());

    private final Map<String, StateValue> values;

    /**
     * Creates a detached immutable state view.
     *
     * @param values JSON-shaped values
     */
    public AgentSessionStateBag(Map<String, ? extends StateValue> values) {
        AgentValidation.requireNonNull(values, "values");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                AgentValidation.requireNonBlank(key, "state key"),
                AgentValidation.requireNonNull(value, "state value")));
        this.values = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the shared empty state view.
     *
     * @return empty state bag
     */
    public static AgentSessionStateBag empty() {
        return EMPTY;
    }

    /**
     * Returns a value when present.
     *
     * @param key state key
     * @return optional immutable state value
     */
    public Optional<StateValue> get(String key) {
        return Optional.ofNullable(values.get(AgentValidation.requireNonBlank(key, "key")));
    }

    /**
     * Returns whether a key is present.
     *
     * @param key state key
     * @return {@code true} when present
     */
    public boolean containsKey(String key) {
        return values.containsKey(AgentValidation.requireNonBlank(key, "key"));
    }

    /**
     * Returns the number of state entries.
     *
     * @return entry count
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns an immutable detached map view.
     *
     * @return state values
     */
    public Map<String, StateValue> values() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof AgentSessionStateBag bag && values.equals(bag.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
