// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.time.Instant;

/**
 * Represents one client lifecycle event emitted by the official SDK.
 *
 * @param type framework classification
 * @param upstreamType exact official SDK event type
 * @param sessionId session identity
 * @param startedAt optional session start time
 * @param modifiedAt optional session modification time
 * @param summary optional session summary
 */
public record GitHubCopilotSessionLifecycleEvent(
        GitHubCopilotSessionLifecycleEventType type,
        String upstreamType,
        String sessionId,
        Instant startedAt,
        Instant modifiedAt,
        String summary) {
    /** Creates a validated lifecycle event. */
    public GitHubCopilotSessionLifecycleEvent {
        if (type == null
                || upstreamType == null
                || upstreamType.isBlank()
                || sessionId == null
                || sessionId.isBlank()) {
            throw new IllegalArgumentException("type, upstreamType, and sessionId are required.");
        }
    }
}
