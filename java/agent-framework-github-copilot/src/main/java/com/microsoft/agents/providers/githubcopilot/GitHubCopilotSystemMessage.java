// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Configures a framework-owned system-message override.
 *
 * @param mode replacement mode
 * @param content non-blank content
 */
public record GitHubCopilotSystemMessage(Mode mode, String content) {
    /** Creates a validated system message. */
    public GitHubCopilotSystemMessage {
        if (mode == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("mode and non-blank content are required.");
        }
    }

    /** Supported upstream system-message modes. */
    public enum Mode {
        /** Appends content to the upstream default system message. */
        APPEND,
        /** Replaces the upstream default system message. */
        REPLACE
    }
}
