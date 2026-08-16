// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Persists service continuation identifiers under an authenticated partition.
 *
 * @param key authenticated partition
 * @param threadId service thread
 * @param runId optional latest run
 * @param submittedMessageIds stable framework message identifiers already reserved for submission
 * @param revision optimistic revision, or {@code -1} before first save
 * @param createdAt creation time
 * @param updatedAt update time
 */
public record FoundryHostedSession(
        FoundryHostedSessionKey key,
        String threadId,
        String runId,
        List<String> submittedMessageIds,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    /** Revision used for create-only saves. */
    public static final long CREATE_ONLY = -1;

    /** Creates and validates a hosted session. */
    public FoundryHostedSession {
        key = java.util.Objects.requireNonNull(key, "key");
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank.");
        }
        if (runId != null && runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank.");
        }
        if (revision != CREATE_ONLY && revision <= 0) {
            throw new IllegalArgumentException("revision must be CREATE_ONLY or positive.");
        }
        submittedMessageIds = List.copyOf(java.util.Objects.requireNonNull(submittedMessageIds, "submittedMessageIds"));
        if (submittedMessageIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("submittedMessageIds must contain only non-blank identifiers.");
        }
        if (new LinkedHashSet<>(submittedMessageIds).size() != submittedMessageIds.size()) {
            throw new IllegalArgumentException("submittedMessageIds must not contain duplicates.");
        }
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Creates a hosted session without submitted message identifiers.
     *
     * @param key authenticated partition
     * @param threadId service thread
     * @param runId optional latest run
     * @param revision optimistic revision, or {@code -1} before first save
     * @param createdAt creation time
     * @param updatedAt update time
     */
    public FoundryHostedSession(
            FoundryHostedSessionKey key,
            String threadId,
            String runId,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        this(key, threadId, runId, List.of(), revision, createdAt, updatedAt);
    }

    /**
     * Creates an unsaved hosted session.
     *
     * @param key authenticated partition
     * @param threadId service thread
     * @param now creation time
     * @return session
     */
    public static FoundryHostedSession create(FoundryHostedSessionKey key, String threadId, Instant now) {
        return new FoundryHostedSession(key, threadId, null, List.of(), CREATE_ONLY, now, now);
    }
}
