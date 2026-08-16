// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

final class MemoryValidation {
    private MemoryValidation() {}

    static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalNonBlank(String value, String name) {
        return value == null ? null : requireNonBlank(value, name);
    }
}
