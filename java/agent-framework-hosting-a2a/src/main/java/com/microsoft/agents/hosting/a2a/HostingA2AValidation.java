// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

final class HostingA2AValidation {
    private HostingA2AValidation() {}

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
