// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Represents one event in an A2A streaming response. */
public sealed interface A2AStreamEvent permits Message, Task, TaskArtifactUpdateEvent, TaskStatusUpdateEvent {
    /**
     * Returns the JSON stream discriminator.
     *
     * @return event kind
     */
    String kind();
}
