// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CoreValidation {
    private CoreValidation() {}

    static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new ValidationException(name + " must not be blank when present.");
        }
        return value;
    }

    static Map<String, StateValue> copyStateMap(Map<String, StateValue> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) ->
                copy.put(requireNonBlank(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    static <T> List<T> copyList(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        values.forEach(value -> copy.add(Objects.requireNonNull(value, name + " element")));
        return List.copyOf(copy);
    }

    static Map<String, StateValue> mergeStateMaps(Map<String, StateValue> first, Map<String, StateValue> second) {
        LinkedHashMap<String, StateValue> merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return Collections.unmodifiableMap(merged);
    }
}
