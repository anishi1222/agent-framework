// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.ValidationException;
import java.time.Instant;

/**
 * Represents one immutable durable memory record.
 *
 * @param key scoped record key
 * @param content untrusted memory text
 * @param metadata immutable metadata
 * @param embedding optional embedding
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param timeToLiveSeconds optional positive item TTL
 */
public record MemoryRecord(
        MemoryKey key,
        String content,
        MemoryMetadata metadata,
        EmbeddingVector embedding,
        Instant createdAt,
        Instant updatedAt,
        Integer timeToLiveSeconds) {
    /** Creates a validated immutable memory record. */
    public MemoryRecord {
        key = MemoryValidation.requireNonNull(key, "key");
        content = MemoryValidation.requireNonBlank(content, "content");
        metadata = metadata == null ? MemoryMetadata.empty() : metadata;
        createdAt = MemoryValidation.requireNonNull(createdAt, "createdAt");
        updatedAt = MemoryValidation.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new ValidationException("updatedAt must not precede createdAt.");
        }
        if (timeToLiveSeconds != null && timeToLiveSeconds <= 0) {
            throw new ValidationException("timeToLiveSeconds must be positive when present.");
        }
    }

    /**
     * Creates a record using the same creation and update timestamp.
     *
     * @param key scoped key
     * @param content memory text
     * @param metadata metadata
     * @param embedding optional embedding
     * @param timestamp creation and update time
     * @return immutable record
     */
    public static MemoryRecord create(
            MemoryKey key, String content, MemoryMetadata metadata, EmbeddingVector embedding, Instant timestamp) {
        return new MemoryRecord(key, content, metadata, embedding, timestamp, timestamp, null);
    }
}
