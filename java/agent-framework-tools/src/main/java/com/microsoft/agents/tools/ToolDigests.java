// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Objects;

final class ToolDigests {
    private ToolDigests() {}

    static String state(StateValue value) {
        MessageDigest digest = sha256();
        updateValue(digest, value);
        return hex(digest.digest());
    }

    static String strings(String... values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            byte[] encoded =
                    ToolValidation.requireNonBlank(value, "digest value").getBytes(StandardCharsets.UTF_8);
            digest.update((byte) 0x01);
            updateLength(digest, encoded.length);
            digest.update(encoded);
        }
        return hex(digest.digest());
    }

    private static void updateValue(MessageDigest digest, StateValue value) {
        switch (value) {
            case StateValue.ObjectValue object -> {
                digest.update((byte) 'o');
                object.values().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .forEach(entry -> {
                            updateString(digest, entry.getKey());
                            updateValue(digest, entry.getValue());
                        });
                digest.update((byte) 'O');
            }
            case StateValue.ArrayValue array -> {
                digest.update((byte) 'a');
                array.values().forEach(item -> updateValue(digest, item));
                digest.update((byte) 'A');
            }
            case StateValue.StringValue string -> {
                digest.update((byte) 's');
                updateString(digest, string.value());
            }
            case StateValue.NumberValue number -> {
                digest.update((byte) 'n');
                updateString(digest, number.value().toPlainString());
            }
            case StateValue.BooleanValue bool -> digest.update(bool.value() ? (byte) 't' : (byte) 'f');
            case StateValue.NullValue nullValue -> {
                Objects.requireNonNull(nullValue, "nullValue");
                digest.update((byte) '0');
            }
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        updateLength(digest, encoded.length);
        digest.update(encoded);
    }

    private static void updateLength(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
