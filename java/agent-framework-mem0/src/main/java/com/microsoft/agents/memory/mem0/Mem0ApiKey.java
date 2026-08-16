// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.ValidationException;
import java.util.Objects;

/**
 * Holds a Mem0 Platform API key without exposing it through logs or string conversion.
 */
public final class Mem0ApiKey {
    private static final int MAX_KEY_LENGTH = 16 * 1024;

    private final String value;

    private Mem0ApiKey(String value) {
        this.value = validate(value);
    }

    /**
     * Wraps a Mem0 Platform API key.
     *
     * @param value non-blank API key
     * @return redacted key wrapper
     */
    public static Mem0ApiKey of(String value) {
        return new Mem0ApiKey(value);
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "Mem0ApiKey[REDACTED]";
    }

    private static String validate(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new ValidationException("Mem0 API key must not be blank.");
        }
        if (value.length() > MAX_KEY_LENGTH) {
            throw new ValidationException("Mem0 API key exceeds the supported length.");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new ValidationException("Mem0 API key must not contain line breaks.");
        }
        return value;
    }
}
