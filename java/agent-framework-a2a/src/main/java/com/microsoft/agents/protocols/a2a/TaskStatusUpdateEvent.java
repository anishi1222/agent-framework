// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Announces a task status transition.
 *
 * @param taskId task identifier
 * @param contextId context identifier
 * @param status new status
 * @param metadata immutable event metadata
 */
public record TaskStatusUpdateEvent(
        String taskId, String contextId, TaskStatus status, Map<String, StateValue> metadata)
        implements A2AStreamEvent {
    /** Creates a correlated status event. */
    public TaskStatusUpdateEvent {
        taskId = A2AValidation.nonBlank(taskId, "taskId");
        contextId = A2AValidation.nonBlank(contextId, "contextId");
        status = Objects.requireNonNull(status, "status");
        metadata = A2AValidation.metadata(metadata, "metadata");
    }

    @Override
    public String kind() {
        return "statusUpdate";
    }
}
