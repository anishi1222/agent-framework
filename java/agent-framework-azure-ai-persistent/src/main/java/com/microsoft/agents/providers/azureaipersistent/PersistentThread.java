// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.time.Instant;
import java.util.Map;

/**
 * Represents one service-owned thread.
 *
 * @param id thread identifier
 * @param createdAt optional creation time
 * @param metadata immutable metadata
 */
public record PersistentThread(String id, Instant createdAt, Map<String, String> metadata) {
    /** Creates and validates a thread. */
    public PersistentThread {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
