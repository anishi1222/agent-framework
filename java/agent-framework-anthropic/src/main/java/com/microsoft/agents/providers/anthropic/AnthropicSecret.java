// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import java.util.Objects;

/**
 * Holds an Anthropic API key with redacted diagnostics.
 */
public final class AnthropicSecret {
    private final String value;

    private AnthropicSecret(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        this.value = value;
    }

    /** Wraps an API key. */
    public static AnthropicSecret of(String value) {
        return new AnthropicSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
