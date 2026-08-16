// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.core.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class CosmosCursorCodec {
    private static final String PREFIX = "afc1.";

    private static final int MAX_CURSOR_LENGTH = 32_768;

    private CosmosCursorCodec() {}

    static String encode(String partitionKey, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX
                + encoder.encodeToString(partitionKey.getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String partitionKey, String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH || !cursor.startsWith(PREFIX)) {
            throw new ValidationException("Cosmos continuation cursor is malformed.");
        }
        String[] parts = cursor.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            throw new ValidationException("Cosmos continuation cursor is malformed.");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String decodedPartition = new String(decoder.decode(parts[0]), StandardCharsets.UTF_8);
            String token = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
            if (!partitionKey.equals(decodedPartition) || token.isBlank()) {
                throw new ValidationException("Cosmos continuation cursor belongs to another partition.");
            }
            return token;
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Cosmos continuation cursor is malformed.");
        }
    }
}
