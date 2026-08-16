// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.microsoft.agents.core.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents one thread message.
 *
 * @param id service message identifier
 * @param threadId owning thread
 * @param runId optional producing run
 * @param role framework role
 * @param text concatenated text content
 * @param attachments immutable attachments
 * @param metadata immutable service metadata
 * @param createdAt optional creation time
 */
public record PersistentMessage(
        String id,
        String threadId,
        String runId,
        Role role,
        String text,
        List<PersistentAttachment> attachments,
        Map<String, String> metadata,
        Instant createdAt) {
    /** Creates and defensively copies a message. */
    public PersistentMessage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank.");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank.");
        }
        role = java.util.Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
