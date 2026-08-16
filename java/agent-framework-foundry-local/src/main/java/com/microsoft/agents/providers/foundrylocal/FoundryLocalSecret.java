// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import java.util.Objects;

/**
 * Holds a reverse-proxy bearer token with redacted diagnostics.
 */
public final class FoundryLocalSecret {
    private final String value;

    private FoundryLocalSecret(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
        this.value = value;
    }

    /** Wraps a bearer token. */
    public static FoundryLocalSecret of(String value) {
        return new FoundryLocalSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
