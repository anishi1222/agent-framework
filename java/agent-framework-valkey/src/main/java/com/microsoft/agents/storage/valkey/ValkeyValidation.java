// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.ValidationException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

final class ValkeyValidation {
    private ValkeyValidation() {}

    static <T> T requireNonNull(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String boundedIdentifier(String value, String name, int maxUtf8Bytes) {
        String checked = requireNonBlank(value, name);
        if (utf8(checked, name).length > maxUtf8Bytes) {
            throw new ValidationException(name + " must not exceed " + maxUtf8Bytes + " UTF-8 bytes.");
        }
        return checked;
    }

    static String optionalBoundedIdentifier(String value, String name, int maxUtf8Bytes) {
        return value == null ? null : boundedIdentifier(value, name, maxUtf8Bytes);
    }

    static String host(String value) {
        String checked = boundedIdentifier(value, "host", 253);
        if (!checked.equals(checked.trim())
                || checked.chars().anyMatch(Character::isWhitespace)
                || checked.chars().anyMatch(Character::isISOControl)
                || checked.indexOf('/') >= 0
                || checked.indexOf('\\') >= 0
                || checked.indexOf('?') >= 0
                || checked.indexOf('#') >= 0
                || checked.indexOf('@') >= 0
                || checked.indexOf('{') >= 0
                || checked.indexOf('}') >= 0) {
            throw new ValidationException("host must be an exact DNS name or IP literal without URI syntax.");
        }
        return checked;
    }

    static String keyPrefix(String value) {
        String checked = boundedIdentifier(value, "keyPrefix", 128);
        if (!checked.equals(checked.trim())
                || checked.chars().anyMatch(Character::isWhitespace)
                || checked.chars().anyMatch(Character::isISOControl)
                || checked.indexOf('{') >= 0
                || checked.indexOf('}') >= 0) {
            throw new ValidationException("keyPrefix must contain no whitespace, controls, or hash-tag braces.");
        }
        return checked;
    }

    static Duration operationTimeout(Duration value) {
        Duration checked = requireNonNull(value, "operationTimeout");
        long millis;
        try {
            millis = checked.toMillis();
        } catch (ArithmeticException exception) {
            throw new ValidationException("operationTimeout is outside the supported range.", exception);
        }
        if (checked.isNegative() || checked.isZero() || millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new ValidationException(
                    "operationTimeout must be between 1 millisecond and " + Integer.MAX_VALUE + " milliseconds.");
        }
        return checked;
    }

    static byte[] utf8(String value, String name) {
        String checked = requireNonNull(value, name);
        try {
            var encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(checked));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new ValidationException(name + " must contain well-formed Unicode.", exception);
        }
    }
}
