// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.time.Instant;

/**
 * Defines content and trusted identity metadata for Purview evaluation.
 *
 * @param userId Entra user object identifier
 * @param tenantId Entra tenant identifier
 * @param correlationId stable conversation correlation
 * @param messageId stable message identifier
 * @param sequenceNumber monotonically increasing conversation sequence
 * @param activity protected activity
 * @param text content text
 * @param createdAt content creation time
 * @param location protected application location
 * @param appName application name
 * @param appVersion application version
 */
public record PurviewContentRequest(
        String userId,
        String tenantId,
        String correlationId,
        String messageId,
        long sequenceNumber,
        PurviewActivity activity,
        String text,
        Instant createdAt,
        PurviewAppLocation location,
        String appName,
        String appVersion) {
    /** Creates and validates a content request. */
    public PurviewContentRequest {
        userId = required(userId, "userId");
        tenantId = required(tenantId, "tenantId");
        correlationId = required(correlationId, "correlationId");
        messageId = required(messageId, "messageId");
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must not be negative.");
        }
        activity = java.util.Objects.requireNonNull(activity, "activity");
        text = java.util.Objects.requireNonNull(text, "text");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        location = java.util.Objects.requireNonNull(location, "location");
        appName = required(appName, "appName");
        appVersion = required(appVersion, "appVersion");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
