// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents hidden context supplied to an agent as a system message.
 *
 * @param id stable item identifier
 * @param threadId owning thread identifier
 * @param hiddenContextType hidden-context wire discriminator
 * @param content context text
 * @param createdAt optional wire creation time
 */
public record ChatKitHiddenContextItem(
        String id, String threadId, ChatKitHiddenContextType hiddenContextType, String content, Instant createdAt)
        implements ChatKitThreadItem {

    /** Validates and creates a hidden-context item. */
    public ChatKitHiddenContextItem {
        id = requireNonBlank(id, "id");
        threadId = requireNonBlank(threadId, "threadId");
        hiddenContextType = Objects.requireNonNull(hiddenContextType, "hiddenContextType");
        content = Objects.requireNonNull(content, "content");
    }

    /** Returns the selected hidden-context wire discriminator. */
    @Override
    public String type() {
        return hiddenContextType.wireValue();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
