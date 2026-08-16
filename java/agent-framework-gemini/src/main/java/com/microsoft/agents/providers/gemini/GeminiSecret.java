// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import java.util.Objects;

/**
 * Holds a Gemini API key with redacted diagnostics.
 */
public final class GeminiSecret {
    private final String value;

    private GeminiSecret(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        this.value = value;
    }

    /** Wraps an API key. */
    public static GeminiSecret of(String value) {
        return new GeminiSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
