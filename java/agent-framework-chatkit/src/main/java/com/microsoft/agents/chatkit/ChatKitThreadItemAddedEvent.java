// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Announces an empty assistant message before text deltas are streamed.
 *
 * @param item assistant message being added
 */
public record ChatKitThreadItemAddedEvent(ChatKitAssistantMessageItem item) implements ChatKitThreadEvent {

    /** Validates and creates an item-added event. */
    public ChatKitThreadItemAddedEvent {
        item = Objects.requireNonNull(item, "item");
        if (item.createdAt() == null) {
            throw new IllegalArgumentException("An item-added event requires createdAt.");
        }
        if (!item.outputTextParts().isEmpty()) {
            throw new IllegalArgumentException("An item-added event must start with empty content.");
        }
    }

    /** Returns the item-added event discriminator. */
    @Override
    public String type() {
        return "thread.item.added";
    }
}
