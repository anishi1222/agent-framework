// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

/**
 * Identifies a hosted continuation partition.
 *
 * @param routeId hosted route
 * @param principalId authenticated principal
 * @param isolationId independently derived isolation partition
 * @param conversationId authorized conversation key
 */
public record FoundryHostedSessionKey(String routeId, String principalId, String isolationId, String conversationId) {
    /** Creates and validates a hosted session key. */
    public FoundryHostedSessionKey {
        routeId = safe(routeId, "routeId");
        principalId = safe(principalId, "principalId");
        isolationId = safe(isolationId, "isolationId");
        conversationId = safe(conversationId, "conversationId");
    }

    private static String safe(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must contain 1 to 256 characters.");
        }
        if (value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0
                || value.equals(".")
                || value.equals("..")) {
            throw new IllegalArgumentException(name + " must be an opaque safe identifier.");
        }
        return value;
    }
}
