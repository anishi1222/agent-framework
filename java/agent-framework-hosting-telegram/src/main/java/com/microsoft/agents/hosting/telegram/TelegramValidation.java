// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.time.Duration;
import java.util.Objects;

final class TelegramValidation {
    private TelegramValidation() {}

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    static String wellFormedUtf16(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!isWellFormedUtf16(value)) {
            throw new IllegalArgumentException(name + " must contain well-formed UTF-16.");
        }
        return value;
    }

    static boolean isWellFormedUtf16(String value) {
        Objects.requireNonNull(value, "value");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }
}
