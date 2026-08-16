// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Filters externally persisted sessions through the official SDK's exact-match filter.
 *
 * @param workingDirectory optional exact working-directory match
 * @param gitRoot optional exact Git root match
 * @param repository optional exact repository match
 * @param branch optional exact branch match
 */
public record GitHubCopilotSessionFilter(String workingDirectory, String gitRoot, String repository, String branch) {
    /** Normalizes blank filter values to absent values. */
    public GitHubCopilotSessionFilter {
        workingDirectory = optional(workingDirectory);
        gitRoot = optional(gitRoot);
        repository = optional(repository);
        branch = optional(branch);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
