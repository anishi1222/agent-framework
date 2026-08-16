// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import java.util.Objects;

/**
 * Holds a Mistral credential without exposing it through diagnostic strings.
 */
public final class MistralSecret {
    private final String value;

    private MistralSecret(String value) {
        this.value = requireNonBlank(value);
    }

    /**
     * Wraps a non-blank secret.
     *
     * @param value secret value
     * @return redacting secret wrapper
     */
    public static MistralSecret of(String value) {
        return new MistralSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MistralSecret secret && value.equals(secret.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    private static String requireNonBlank(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        return value;
    }
}
