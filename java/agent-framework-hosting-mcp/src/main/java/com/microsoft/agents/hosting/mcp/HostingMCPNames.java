// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class HostingMCPNames {
    private HostingMCPNames() {}

    static String normalize(String value) {
        String source = HostingMCPValidation.nonBlank(value, "name");
        String normalized =
                source.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_").replaceAll("^[_-]+|[_-]+$", "");
        if (normalized.isEmpty()) {
            normalized = "agent";
        }
        if (normalized.length() <= 128) {
            return normalized;
        }
        return normalized.substring(0, 119) + "_" + hash(source);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
