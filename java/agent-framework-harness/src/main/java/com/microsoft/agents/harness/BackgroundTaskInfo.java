// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/**
 * Describes one persisted background-agent task.
 *
 * @param id positive parent-session task identifier
 * @param agentName configured agent name
 * @param description human-readable task description
 * @param status current lifecycle status
 * @param resultText successful result text, when complete
 * @param errorText failure, cancellation, or lost-task description
 */
public record BackgroundTaskInfo(
        int id,
        String agentName,
        String description,
        BackgroundTaskStatus status,
        String resultText,
        String errorText) {
    /** Creates a validated immutable task descriptor. */
    public BackgroundTaskInfo {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be greater than zero.");
        }
        agentName = requireNonBlank(agentName, "agentName");
        description = requireNonBlank(description, "description");
        if (status == null) {
            throw new NullPointerException("status");
        }
        if (status == BackgroundTaskStatus.COMPLETED && resultText == null) {
            throw new IllegalArgumentException("Completed tasks require resultText.");
        }
        if ((status == BackgroundTaskStatus.FAILED
                        || status == BackgroundTaskStatus.CANCELLED
                        || status == BackgroundTaskStatus.LOST)
                && errorText == null) {
            throw new IllegalArgumentException("Failed, cancelled, and lost tasks require errorText.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
