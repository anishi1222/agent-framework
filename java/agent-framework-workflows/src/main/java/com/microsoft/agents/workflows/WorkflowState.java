// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Represents an immutable workflow state snapshot at a superstep boundary. */
public final class WorkflowState {
    private static final WorkflowState EMPTY = new WorkflowState(Map.of());

    private final Map<String, EncodedState> values;

    /**
     * Creates a detached immutable state snapshot.
     *
     * @param values encoded values keyed by stable state name
     */
    public WorkflowState(Map<String, EncodedState> values) {
        Objects.requireNonNull(values, "values");
        TreeMap<String, EncodedState> sorted = new TreeMap<>();
        values.forEach((key, value) -> sorted.put(
                WorkflowValidation.requireNonBlank(key, "state key"),
                Objects.requireNonNull(value, "encoded state value")));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    /**
     * Returns an empty state snapshot.
     *
     * @return shared empty snapshot
     */
    public static WorkflowState empty() {
        return EMPTY;
    }

    /**
     * Creates a typed state builder.
     *
     * @return empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads one typed state value.
     *
     * @param key typed state key
     * @param <T> decoded value type
     * @return decoded value when present
     */
    public <T> Optional<T> get(StateKey<T> key) {
        Objects.requireNonNull(key, "key");
        EncodedState encoded = values.get(key.name());
        return encoded == null ? Optional.empty() : Optional.of(key.decode(encoded));
    }

    /**
     * Returns the canonical encoded values.
     *
     * @return immutable values sorted by key
     */
    public Map<String, EncodedState> values() {
        return values;
    }

    WorkflowState with(Map<String, EncodedState> replacements) {
        LinkedHashMap<String, EncodedState> merged = new LinkedHashMap<>(values);
        merged.putAll(replacements);
        return new WorkflowState(merged);
    }

    /** Builds a typed immutable workflow state snapshot. */
    public static final class Builder {
        private final LinkedHashMap<String, EncodedState> values = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Adds or replaces one typed value.
         *
         * @param key typed state key
         * @param value decoded state value
         * @param <T> state value type
         * @return this builder
         */
        public <T> Builder put(StateKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            values.put(key.name(), key.encode(value));
            return this;
        }

        /**
         * Creates the immutable state snapshot.
         *
         * @return state snapshot
         */
        public WorkflowState build() {
            return values.isEmpty() ? WorkflowState.empty() : new WorkflowState(values);
        }
    }
}
