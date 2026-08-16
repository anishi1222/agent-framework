// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.Objects;

final class HostingMCPValidation {
    private HostingMCPValidation() {}

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

    static int port(int value) {
        if (value < 0 || value > 65_535) {
            throw new ValidationException("port must be between 0 and 65535.");
        }
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }
}
