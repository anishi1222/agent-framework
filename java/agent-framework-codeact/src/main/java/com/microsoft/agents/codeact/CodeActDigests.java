// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CodeActDigests {
    private CodeActDigests() {}

    static String programDigest(CodeActOptions options, CodeActProgram program) {
        MessageDigest digest = sha256();
        update(digest, options.workspaceRoot().toString());
        update(digest, Integer.toString(options.maxSteps()));
        update(digest, Long.toString(options.timeout().toNanos()));
        update(digest, Integer.toString(options.maxOutputBytes()));
        for (CodeActStep step : program.steps()) {
            update(digest, step.id());
            update(digest, step.command());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String runId(String programDigest) {
        return "codeact-"
                + CodeActValidation.requireNonBlank(programDigest, "programDigest")
                        .substring(0, 24);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }
}
