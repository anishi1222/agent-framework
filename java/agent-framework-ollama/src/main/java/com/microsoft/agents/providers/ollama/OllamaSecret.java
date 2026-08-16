// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import java.util.Objects;

/**
 * Holds an optional Ollama-compatible bearer token with redacted diagnostics.
 */
public final class OllamaSecret {
    private final String value;

    private OllamaSecret(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        this.value = value;
    }

    /** Wraps a bearer token. */
    public static OllamaSecret of(String value) {
        return new OllamaSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
