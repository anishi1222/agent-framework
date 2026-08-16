// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

final class ValkeyKeyDerivation {
    private static final byte[] HISTORY_DOMAIN = "agent-framework-valkey-history-v1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] OPERATION_DOMAIN =
            "agent-framework-valkey-operation-v1".getBytes(StandardCharsets.UTF_8);

    private static final byte[] APPEND_DOMAIN = "agent-framework-valkey-append-v1".getBytes(StandardCharsets.UTF_8);

    private ValkeyKeyDerivation() {}

    static ValkeyHistoryKeys historyKeys(ValkeyHistoryOptions options, String sessionId) {
        String checkedSession = ValkeyValidation.boundedIdentifier(sessionId, "sessionId", 4096);
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, HISTORY_DOMAIN);
        updateLengthPrefixed(digest, utf8(options.partition().tenantId()));
        updateLengthPrefixed(digest, utf8(options.partition().isolationId()));
        updateLengthPrefixed(digest, utf8(options.partition().agentId()));
        updateLengthPrefixed(digest, utf8(checkedSession));
        String hashTag = base64Url(digest.digest());
        String base = options.keyPrefix() + ":{" + hashTag + "}";
        return new ValkeyHistoryKeys(base + ":messages", base + ":dedup", base + ":dedup-order");
    }

    static String operationToken(String operationId) {
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, OPERATION_DOMAIN);
        updateLengthPrefixed(digest, utf8(ValkeyValidation.boundedIdentifier(operationId, "runId", 4096)));
        return base64Url(digest.digest());
    }

    static String appendDigest(List<byte[]> messages) {
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, APPEND_DOMAIN);
        for (byte[] message : messages) {
            updateLengthPrefixed(digest, ValkeyValidation.requireNonNull(message, "message"));
        }
        return base64Url(digest.digest());
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value.length).array());
        digest.update(value);
    }

    private static byte[] utf8(String value) {
        return ValkeyValidation.utf8(value, "identifier");
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
