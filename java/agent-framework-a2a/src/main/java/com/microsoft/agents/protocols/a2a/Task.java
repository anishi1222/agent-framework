// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an immutable A2A task snapshot.
 *
 * @param id task identifier
 * @param contextId conversation context identifier
 * @param status current status
 * @param artifacts accumulated artifacts
 * @param history ordered task history
 * @param metadata immutable metadata
 */
public record Task(
        String id,
        String contextId,
        TaskStatus status,
        List<Artifact> artifacts,
        List<Message> history,
        Map<String, StateValue> metadata)
        implements A2AStreamEvent, SendMessageResult {
    /** Creates a validated detached task snapshot. */
    public Task {
        id = A2AValidation.nonBlank(id, "id");
        contextId = A2AValidation.nonBlank(contextId, "contextId");
        status = Objects.requireNonNull(status, "status");
        artifacts = A2AValidation.list(artifacts, "artifacts");
        history = A2AValidation.list(history, "history");
        metadata = A2AValidation.metadata(metadata, "metadata");
        for (Message message : history) {
            if (message.contextId() != null && !contextId.equals(message.contextId())) {
                throw new com.microsoft.agents.core.ValidationException(
                        "Task history message contextId does not match the task.");
            }
            if (message.taskId() != null && !id.equals(message.taskId())) {
                throw new com.microsoft.agents.core.ValidationException(
                        "Task history message taskId does not match the task.");
            }
        }
    }

    /**
     * Creates a task builder.
     *
     * @param id task identifier
     * @param contextId context identifier
     * @param status current status
     * @return builder
     */
    public static Builder builder(String id, String contextId, TaskStatus status) {
        return new Builder(id, contextId, status);
    }

    @Override
    public String kind() {
        return "task";
    }

    /** Builds an immutable {@link Task}. */
    public static final class Builder {
        private final String id;
        private final String contextId;
        private TaskStatus status;
        private List<Artifact> artifacts = List.of();
        private List<Message> history = List.of();
        private Map<String, StateValue> metadata = Map.of();

        private Builder(String id, String contextId, TaskStatus status) {
            this.id = id;
            this.contextId = contextId;
            this.status = status;
        }

        /** Sets current status. */
        public Builder status(TaskStatus value) {
            status = value;
            return this;
        }

        /** Sets accumulated artifacts. */
        public Builder artifacts(List<Artifact> values) {
            artifacts = values;
            return this;
        }

        /** Sets ordered history. */
        public Builder history(List<Message> values) {
            history = values;
            return this;
        }

        /** Sets task metadata. */
        public Builder metadata(Map<String, StateValue> values) {
            metadata = values;
            return this;
        }

        /** Creates the immutable task. */
        public Task build() {
            return new Task(id, contextId, status, artifacts, history, metadata);
        }
    }
}
