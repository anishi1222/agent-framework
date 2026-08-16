// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.Objects;

/**
 * Holds a supported GitHub Copilot OAuth, GitHub App user, or fine-grained access token.
 *
 * <p>Classic personal access tokens using the {@code ghp_} prefix are rejected by the upstream
 * service and by this type. The token is never rendered by {@link #toString()}.
 */
public final class GitHubCopilotCredential {
    private final String token;

    private GitHubCopilotCredential(String token) {
        String value = Objects.requireNonNull(token, "token");
        if (value.isBlank()) {
            throw new IllegalArgumentException("token must not be blank.");
        }
        if (value.startsWith("ghp_")) {
            throw new IllegalArgumentException("Classic GitHub personal access tokens are not supported.");
        }
        if (!(value.startsWith("gho_") || value.startsWith("ghu_") || value.startsWith("github_pat_"))) {
            throw new IllegalArgumentException(
                    "token must be an OAuth user, GitHub App user, or fine-grained personal access token.");
        }
        this.token = value;
    }

    /**
     * Wraps and validates a token.
     *
     * @param token supported token
     * @return redacting credential wrapper
     */
    public static GitHubCopilotCredential of(String token) {
        return new GitHubCopilotCredential(token);
    }

    String reveal() {
        return token;
    }

    @Override
    public String toString() {
        return "GitHubCopilotCredential[REDACTED]";
    }
}
