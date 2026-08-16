// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Finalizes an assistant message with its accumulated output text.
 *
 * @param item completed assistant message
 */
public record ChatKitThreadItemDoneEvent(ChatKitAssistantMessageItem item) implements ChatKitThreadEvent {

    /** Validates and creates an item-done event. */
    public ChatKitThreadItemDoneEvent {
        item = Objects.requireNonNull(item, "item");
        if (item.createdAt() == null) {
            throw new IllegalArgumentException("An item-done event requires createdAt.");
        }
    }

    /** Returns the item-done event discriminator. */
    @Override
    public String type() {
        return "thread.item.done";
    }
}
