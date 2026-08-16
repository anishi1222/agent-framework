// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class A2AValidation {
    private A2AValidation() {}

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        return value == null ? null : nonBlank(value, name);
    }

    static URI absoluteUri(URI value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute() || value.getHost() == null) {
            throw new ValidationException(name + " must be an absolute hierarchical URI.");
        }
        return value;
    }

    static <T> List<T> list(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " contains null"));
        }
        return List.copyOf(copy);
    }

    static <T> List<T> nonEmptyList(List<? extends T> values, String name) {
        List<T> copy = list(values, name);
        if (copy.isEmpty()) {
            throw new ValidationException(name + " must not be empty.");
        }
        return copy;
    }

    static List<String> strings(List<String> values, String name, boolean allowEmpty) {
        List<String> copy = list(values, name).stream()
                .map(value -> nonBlank(value, name + " entry"))
                .toList();
        if (!allowEmpty && copy.isEmpty()) {
            throw new ValidationException(name + " must not be empty.");
        }
        return copy;
    }

    static Map<String, StateValue> metadata(Map<String, StateValue> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, StateValue> sorted = new TreeMap<>();
        values.forEach((key, value) ->
                sorted.put(nonBlank(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(sorted);
    }

    static <T> Map<String, T> map(Map<String, ? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, T> sorted = new TreeMap<>();
        values.forEach((key, value) ->
                sorted.put(nonBlank(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(sorted);
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new ValidationException(name + " must not be negative.");
        }
        return value;
    }
}
