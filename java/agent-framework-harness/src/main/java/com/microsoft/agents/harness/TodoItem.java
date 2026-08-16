// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/**
 * Represents one persisted harness work item.
 *
 * @param id positive session-local identifier
 * @param title non-blank title
 * @param description optional description
 * @param completed whether the item is complete
 * @param completionReason optional completion reason
 */
public record TodoItem(int id, String title, String description, boolean completed, String completionReason) {
    /** Creates a validated immutable item. */
    public TodoItem {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be greater than zero.");
        }
        title = requireNonBlank(title, "title");
        description = optionalNonBlank(description, "description");
        completionReason = optionalNonBlank(completionReason, "completionReason");
        if (!completed && completionReason != null) {
            throw new IllegalArgumentException("completionReason requires a completed todo.");
        }
    }

    TodoItem complete(String reason) {
        return new TodoItem(id, title, description, true, optionalNonBlank(reason, "reason"));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank when present.");
        }
        return value;
    }
}
