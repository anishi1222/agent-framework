// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.util.Objects;

/** Holds a Telegram credential without exposing it through diagnostic strings. */
public final class TelegramSecret {
    private final String value;

    private TelegramSecret(String value) {
        this.value = TelegramValidation.nonBlank(value, "value");
    }

    /**
     * Wraps a non-blank Telegram credential.
     *
     * @param value credential value
     * @return redacting credential wrapper
     */
    public static TelegramSecret of(String value) {
        return new TelegramSecret(value);
    }

    String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TelegramSecret secret && value.equals(secret.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
