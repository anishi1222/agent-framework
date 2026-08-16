// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class EvaluationValidation {
    private EvaluationValidation() {}

    static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }

    static <T> List<T> copyList(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " element");
        }
        return List.copyOf(values);
    }

    static <V> Map<String, V> copyMap(Map<String, ? extends V> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<String, V> copy = new LinkedHashMap<>();
        values.forEach((key, value) ->
                copy.put(requireNonBlank(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    static double requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
        return value;
    }
}
