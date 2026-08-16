// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Carries an ordered assistant text delta.
 *
 * @param itemId assistant item identifier
 * @param contentIndex target output-text content index
 * @param delta text delta
 */
public record ChatKitThreadItemUpdatedEvent(String itemId, int contentIndex, String delta)
        implements ChatKitThreadEvent {

    /** Validates and creates an item-updated event. */
    public ChatKitThreadItemUpdatedEvent {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank.");
        }
        if (contentIndex < 0) {
            throw new IllegalArgumentException("contentIndex must not be negative.");
        }
        delta = Objects.requireNonNull(delta, "delta");
    }

    /** Returns the item-updated event discriminator. */
    @Override
    public String type() {
        return "thread.item.updated";
    }
}
