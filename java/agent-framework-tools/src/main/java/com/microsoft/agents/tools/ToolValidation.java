// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ToolValidation {
    private ToolValidation() {}

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

    static Map<String, StateValue> copyMetadata(Map<String, StateValue> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) ->
                copy.put(requireNonBlank(key, "metadata key"), Objects.requireNonNull(value, "metadata value")));
        return Collections.unmodifiableMap(copy);
    }
}
