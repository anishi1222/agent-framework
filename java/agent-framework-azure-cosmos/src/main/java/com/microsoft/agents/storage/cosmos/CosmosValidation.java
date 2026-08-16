// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

final class CosmosValidation {
    private CosmosValidation() {}

    static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String resourceId(String value, String name) {
        String checked = requireNonBlank(value, name);
        if (checked.length() > 255
                || checked.indexOf('/') >= 0
                || checked.indexOf('\\') >= 0
                || checked.indexOf('?') >= 0
                || checked.indexOf('#') >= 0) {
            throw new ValidationException(name + " must be at most 255 characters and contain no /, \\, ? or #.");
        }
        return checked;
    }
}
