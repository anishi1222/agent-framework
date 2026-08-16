// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.time.Instant;

/**
 * Describes externally persisted Copilot CLI session metadata.
 *
 * @param sessionId session identity
 * @param startedAt optional start time
 * @param modifiedAt optional modification time
 * @param summary optional summary
 * @param workingDirectory optional external working directory
 * @param gitRoot optional Git root
 * @param repository optional repository identifier
 * @param branch optional branch
 */
public record GitHubCopilotSessionMetadata(
        String sessionId,
        Instant startedAt,
        Instant modifiedAt,
        String summary,
        String workingDirectory,
        String gitRoot,
        String repository,
        String branch) {
    /** Creates validated metadata. */
    public GitHubCopilotSessionMetadata {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank.");
        }
    }
}
