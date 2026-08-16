// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/**
 * Describes one todo to create.
 *
 * @param title non-blank title
 * @param description optional description
 */
public record TodoInput(String title, String description) {
    /** Creates a validated input. */
    public TodoInput {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank.");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank when present.");
        }
    }
}
