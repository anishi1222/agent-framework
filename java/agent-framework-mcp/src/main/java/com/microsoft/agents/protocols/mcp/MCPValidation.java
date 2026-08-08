// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MCPValidation {
    private MCPValidation() {}

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new ValidationException(name + " must be null or non-blank.");
        }
        return value;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }

    static <T> List<T> copyList(List<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        return List.copyOf(values);
    }

    static <T> Set<T> copySet(Set<? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        return Set.copyOf(values);
    }

    static <T> Map<String, T> copyMap(Map<String, ? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> copy.put(nonBlank(key, name + " key"), Objects.requireNonNull(value, name + " value")));
        return Map.copyOf(copy);
    }
}
