// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/**
 * Identifies one todo completion.
 *
 * @param id positive todo identifier
 * @param reason optional completion reason
 */
public record TodoCompletion(int id, String reason) {
    /** Creates a validated completion. */
    public TodoCompletion {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be greater than zero.");
        }
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank when present.");
        }
    }
}
