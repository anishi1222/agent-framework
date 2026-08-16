// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a ChatKit user message.
 *
 * @param id stable item identifier
 * @param threadId owning thread identifier
 * @param textParts ordered user text parts
 * @param attachments ordered attachments
 * @param quotedText optional text quoted by the user
 * @param createdAt optional wire creation time
 */
public record ChatKitUserMessageItem(
        String id,
        String threadId,
        List<String> textParts,
        List<ChatKitAttachment> attachments,
        String quotedText,
        Instant createdAt)
        implements ChatKitThreadItem {

    /** Validates and creates a user message item. */
    public ChatKitUserMessageItem {
        id = requireNonBlank(id, "id");
        threadId = requireNonBlank(threadId, "threadId");
        textParts = List.copyOf(Objects.requireNonNull(textParts, "textParts"));
        attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
        textParts.forEach(part -> Objects.requireNonNull(part, "textParts contains null"));
        attachments.forEach(attachment -> Objects.requireNonNull(attachment, "attachments contains null"));
    }

    /** Returns the user-message wire discriminator. */
    @Override
    public String type() {
        return "user_message";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
