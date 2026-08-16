// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable typed operations over JSON-safe framework metadata maps. */
public final class MetadataValues {
    private MetadataValues() {}

    /**
     * Adds or replaces one typed value.
     *
     * @param metadata source metadata
     * @param key typed key
     * @param value value to encode
     * @param <T> value type
     * @return immutable updated metadata
     */
    public static <T> Map<String, StateValue> with(Map<String, StateValue> metadata, MetadataKey<T> key, T value) {
        LinkedHashMap<String, StateValue> result = copy(metadata);
        result.put(Objects.requireNonNull(key, "key").name(), key.encode(value));
        return Map.copyOf(result);
    }

    /**
     * Adds one typed value only when its key is absent.
     *
     * @param metadata source metadata
     * @param key typed key
     * @param value value to encode
     * @param <T> value type
     * @return immutable metadata
     */
    public static <T> Map<String, StateValue> withIfAbsent(
            Map<String, StateValue> metadata, MetadataKey<T> key, T value) {
        LinkedHashMap<String, StateValue> result = copy(metadata);
        MetadataKey<T> safeKey = Objects.requireNonNull(key, "key");
        if (!result.containsKey(safeKey.name())) {
            result.put(safeKey.name(), safeKey.encode(value));
        }
        return Map.copyOf(result);
    }

    /**
     * Decodes one optional typed value.
     *
     * @param metadata source metadata
     * @param key typed key
     * @param <T> value type
     * @return empty when absent
     */
    public static <T> Optional<T> find(Map<String, StateValue> metadata, MetadataKey<T> key) {
        Map<String, StateValue> safeMetadata = Objects.requireNonNull(metadata, "metadata");
        MetadataKey<T> safeKey = Objects.requireNonNull(key, "key");
        StateValue value = safeMetadata.get(safeKey.name());
        return value == null ? Optional.empty() : Optional.of(safeKey.decode(value));
    }

    /** Returns whether the typed key is present. */
    public static boolean contains(Map<String, StateValue> metadata, MetadataKey<?> key) {
        return Objects.requireNonNull(metadata, "metadata")
                .containsKey(Objects.requireNonNull(key, "key").name());
    }

    /**
     * Removes one typed value.
     *
     * @param metadata source metadata
     * @param key typed key
     * @return immutable updated metadata
     */
    public static Map<String, StateValue> without(Map<String, StateValue> metadata, MetadataKey<?> key) {
        LinkedHashMap<String, StateValue> result = copy(metadata);
        result.remove(Objects.requireNonNull(key, "key").name());
        return Map.copyOf(result);
    }

    private static LinkedHashMap<String, StateValue> copy(Map<String, StateValue> metadata) {
        return new LinkedHashMap<>(CoreValidation.copyStateMap(metadata, "metadata"));
    }
}
