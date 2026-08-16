// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a ChatKit assistant message.
 *
 * @param id stable item identifier
 * @param threadId owning thread identifier
 * @param outputTextParts ordered assistant output-text parts
 * @param createdAt optional wire creation time
 */
public record ChatKitAssistantMessageItem(String id, String threadId, List<String> outputTextParts, Instant createdAt)
        implements ChatKitThreadItem {

    /** Validates and creates an assistant message item. */
    public ChatKitAssistantMessageItem {
        id = requireNonBlank(id, "id");
        threadId = requireNonBlank(threadId, "threadId");
        outputTextParts = List.copyOf(Objects.requireNonNull(outputTextParts, "outputTextParts"));
        outputTextParts.forEach(part -> Objects.requireNonNull(part, "outputTextParts contains null"));
    }

    /** Returns the assistant-message wire discriminator. */
    @Override
    public String type() {
        return "assistant_message";
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
