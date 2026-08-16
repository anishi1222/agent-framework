// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Describes one immutable atomic compaction group.
 *
 * @param id deterministic group identifier
 * @param kind group kind
 * @param messages messages in original chronological order
 * @param messageIndexes indexes in the source list
 * @param turnIndex one-based user turn, zero before the first user turn, or {@code -1} for instructions
 * @param structurallyProtected whether removing the group could orphan an unresolved call, result,
 *     or approval, or remove non-summary preamble content before the first user turn
 * @param estimatedTokens saturating estimated token count
 */
public record CompactionMessageGroup(
        String id,
        CompactionGroupKind kind,
        List<Message> messages,
        List<Integer> messageIndexes,
        int turnIndex,
        boolean structurallyProtected,
        long estimatedTokens) {
    /** Creates and validates an immutable group. */
    public CompactionMessageGroup {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        messages = List.copyOf(messages);
        messageIndexes = List.copyOf(messageIndexes);
        if (messages.isEmpty() || messages.size() != messageIndexes.size()) {
            throw new IllegalArgumentException("messages and messageIndexes must be non-empty and have equal size.");
        }
        if (turnIndex < -1) {
            throw new IllegalArgumentException("turnIndex must be -1 or greater.");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative.");
        }
    }
}
