// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a sensitive OpenAI credential without exposing it through diagnostics.
 *
 * <p>The value is copied on construction and {@link #toString()} always returns a redacted marker.
 * Instances are configuration values and are never serialized by the provider.
 */
public final class OpenAISecret {
    private final char[] value;

    private OpenAISecret(char[] value) {
        this.value = value;
    }

    /**
     * Creates a secret from a non-blank string.
     *
     * @param value sensitive value
     * @return redacting secret wrapper
     */
    public static OpenAISecret of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("OpenAI secret must not be blank.");
        }
        return new OpenAISecret(value.toCharArray());
    }

    /**
     * Creates a secret from copied characters.
     *
     * @param value sensitive characters
     * @return redacting secret wrapper
     */
    public static OpenAISecret of(char[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0 || new String(value).isBlank()) {
            throw new IllegalArgumentException("OpenAI secret must not be blank.");
        }
        return new OpenAISecret(value.clone());
    }

    String reveal() {
        return new String(value);
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof OpenAISecret secret && Arrays.equals(value, secret.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }
}
