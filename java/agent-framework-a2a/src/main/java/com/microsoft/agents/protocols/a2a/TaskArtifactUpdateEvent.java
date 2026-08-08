// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Announces a complete artifact or one artifact chunk.
 *
 * @param taskId task identifier
 * @param contextId context identifier
 * @param artifact artifact or chunk
 * @param append whether the parts append to an existing artifact
 * @param lastChunk whether this is the final chunk for the artifact
 * @param metadata immutable event metadata
 */
public record TaskArtifactUpdateEvent(
        String taskId,
        String contextId,
        Artifact artifact,
        boolean append,
        boolean lastChunk,
        Map<String, StateValue> metadata)
        implements A2AStreamEvent {
    /** Creates a correlated artifact update. */
    public TaskArtifactUpdateEvent {
        taskId = A2AValidation.nonBlank(taskId, "taskId");
        contextId = A2AValidation.nonBlank(contextId, "contextId");
        artifact = Objects.requireNonNull(artifact, "artifact");
        metadata = A2AValidation.metadata(metadata, "metadata");
    }

    @Override
    public String kind() {
        return "artifactUpdate";
    }
}
