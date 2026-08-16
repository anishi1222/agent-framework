// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.Objects;

/**
 * Holds a generic provider or MCP secret without rendering it.
 */
public final class GitHubCopilotSecret {
    private final String value;

    private GitHubCopilotSecret(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank.");
        }
    }

    /**
     * Wraps a secret.
     *
     * @param value secret value
     * @return redacting wrapper
     */
    public static GitHubCopilotSecret of(String value) {
        return new GitHubCopilotSecret(value);
    }

    String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "GitHubCopilotSecret[REDACTED]";
    }
}
