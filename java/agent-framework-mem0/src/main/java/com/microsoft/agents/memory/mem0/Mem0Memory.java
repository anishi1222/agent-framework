// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents one validated Mem0 memory returned by search or list.
 *
 * @param id stable memory identifier
 * @param memory extracted memory text
 * @param score search score in the inclusive range {@code [0, 1]}, or {@code null}
 * @param rank one-based service rank for search results, or zero for unranked operations
 * @param appId optional app identity
 * @param userId optional user identity
 * @param agentId optional agent identity
 * @param runId optional run identity
 * @param metadata immutable JSON-shaped metadata
 * @param categories immutable categories
 * @param createdAt optional creation time
 * @param updatedAt optional update time
 */
public record Mem0Memory(
        String id,
        String memory,
        Double score,
        int rank,
        String appId,
        String userId,
        String agentId,
        String runId,
        Map<String, StateValue> metadata,
        List<String> categories,
        Instant createdAt,
        Instant updatedAt) {
    /** Creates and defensively copies a memory value. */
    public Mem0Memory {
        id = nonBlank(id, "id");
        memory = nonBlank(memory, "memory");
        if (score != null && (!Double.isFinite(score) || score < 0.0 || score > 1.0)) {
            throw new ValidationException("score must be finite and between 0 and 1.");
        }
        if (rank < 0) {
            throw new ValidationException("rank must not be negative.");
        }
        metadata = Map.copyOf(java.util.Objects.requireNonNull(metadata, "metadata"));
        categories = List.copyOf(java.util.Objects.requireNonNull(categories, "categories"));
        for (String category : categories) {
            nonBlank(category, "category");
        }
    }

    @Override
    public String toString() {
        return "Mem0Memory{id=[REDACTED], memory=[REDACTED], score="
                + score
                + ", rank="
                + rank
                + ", appId="
                + present(appId)
                + ", userId="
                + present(userId)
                + ", agentId="
                + present(agentId)
                + ", runId="
                + present(runId)
                + ", metadataEntries="
                + metadata.size()
                + ", categories="
                + categories.size()
                + ", createdAt="
                + createdAt
                + ", updatedAt="
                + updatedAt
                + '}';
    }

    private static String nonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    private static String present(String value) {
        return value == null ? "<absent>" : "[REDACTED]";
    }
}
