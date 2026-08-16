// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class HttpHostingValidation {
    private HttpHostingValidation() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
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
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new ValidationException(name + " must be greater than zero.");
        }
        return value;
    }

    static Set<String> strings(Set<String> values, String name, boolean lowerCase) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        values.forEach(value -> {
            String checked = nonBlank(value, name + " entry");
            String normalized = lowerCase ? checked.toLowerCase(Locale.ROOT) : checked;
            if (!copy.add(normalized)) {
                throw new ValidationException(name + " contains a duplicate entry.");
            }
        });
        return Set.copyOf(copy);
    }
}
