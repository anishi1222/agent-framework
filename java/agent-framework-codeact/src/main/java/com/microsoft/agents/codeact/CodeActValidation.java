// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.util.List;
import java.util.Objects;

final class CodeActValidation {
    private CodeActValidation() {}

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

    static <T> List<T> copyList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " contains null.");
        }
        return List.copyOf(values);
    }
}
