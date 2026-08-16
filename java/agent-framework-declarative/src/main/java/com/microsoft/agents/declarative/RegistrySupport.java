// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class RegistrySupport {
    private RegistrySupport() {}

    static <T> Map<String, T> copy(Map<String, ? extends T> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, T> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(
                AgentDefinitionValidation.requireNonBlank(key, name + " key"),
                Objects.requireNonNull(value, name + " value")));
        return Collections.unmodifiableMap(copy);
    }

    static String key(String value, String name) {
        return AgentDefinitionValidation.requireNonBlank(value, name);
    }
}
