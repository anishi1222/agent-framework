// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a persistent run and its terminal or continuation state.
 *
 * @param id run identifier
 * @param threadId thread identifier
 * @param agentId agent identifier
 * @param status expandable status
 * @param requiredAction optional required action
 * @param errorCode optional service error code
 * @param errorMessage optional sanitized service error message
 * @param usage optional terminal usage
 * @param createdAt optional creation time
 * @param completedAt optional completion time
 * @param metadata immutable service metadata
 */
public record PersistentRun(
        String id,
        String threadId,
        String agentId,
        PersistentRunStatus status,
        PersistentRequiredAction requiredAction,
        String errorCode,
        String errorMessage,
        PersistentRunUsage usage,
        Instant createdAt,
        Instant completedAt,
        Map<String, String> metadata) {
    /** Creates and validates a run. */
    public PersistentRun {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank.");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank.");
        }
        status = java.util.Objects.requireNonNull(status, "status");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
