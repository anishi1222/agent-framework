// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import java.util.Objects;

/**
 * Represents an unsupported bounded thread item retained only for ignore-or-reject policy handling.
 *
 * @param id stable item identifier
 * @param threadId owning thread identifier
 * @param type unsupported wire discriminator
 */
public record ChatKitUnsupportedThreadItem(String id, String threadId, String type) implements ChatKitThreadItem {

    /** Validates and creates an unsupported thread item marker. */
    public ChatKitUnsupportedThreadItem {
        id = requireNonBlank(id, "id");
        threadId = requireNonBlank(threadId, "threadId");
        type = requireNonBlank(type, "type");
        if (isSupported(type)) {
            throw new IllegalArgumentException("A supported discriminator cannot be unsupported.");
        }
    }

    private static boolean isSupported(String value) {
        return value.equals("user_message")
                || value.equals("assistant_message")
                || value.equals("hidden_context_item")
                || value.equals("sdk_hidden_context");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
