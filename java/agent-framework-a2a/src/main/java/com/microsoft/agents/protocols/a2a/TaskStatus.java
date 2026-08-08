// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.time.Instant;
import java.util.Objects;

/**
 * Captures a task state at a point in time.
 *
 * @param state task state
 * @param message optional agent-authored status message
 * @param timestamp status timestamp
 */
public record TaskStatus(TaskState state, Message message, Instant timestamp) {
    /** Creates an immutable validated status. */
    public TaskStatus {
        state = Objects.requireNonNull(state, "state");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        if (message != null && message.role() != Role.ROLE_AGENT) {
            throw new com.microsoft.agents.core.ValidationException("Task status messages must use ROLE_AGENT.");
        }
    }

    /**
     * Creates a status without a message.
     *
     * @param state task state
     * @param timestamp timestamp
     */
    public TaskStatus(TaskState state, Instant timestamp) {
        this(state, null, timestamp);
    }
}
