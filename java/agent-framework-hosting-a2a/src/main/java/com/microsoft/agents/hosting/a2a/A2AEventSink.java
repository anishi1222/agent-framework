// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.a2a.Artifact;
import com.microsoft.agents.protocols.a2a.Message;
import com.microsoft.agents.protocols.a2a.Task;
import com.microsoft.agents.protocols.a2a.TaskState;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Applies validated status and artifact transitions to one hosted task. */
public interface A2AEventSink {
    /** Returns the latest task snapshot. */
    Task current();

    /** Transitions the task to a new state with an optional agent message. */
    CompletionStage<Task> updateStatusAsync(TaskState state, Message message);

    /** Adds or appends one artifact update. */
    CompletionStage<Task> addArtifactAsync(
            Artifact artifact, boolean append, boolean lastChunk, Map<String, StateValue> metadata);
}
