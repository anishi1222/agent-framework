// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Decides whether a requested Copilot operation may proceed.
 */
@FunctionalInterface
public interface GitHubCopilotPermissionHandler {
    /**
     * Handles one correlated permission request.
     *
     * @param request permission request
     * @return explicit response stage
     */
    CompletionStage<GitHubCopilotPermissionResponse> handleAsync(GitHubCopilotPermissionRequest request);

    /**
     * Returns a deny-by-default handler.
     *
     * @return deny handler
     */
    static GitHubCopilotPermissionHandler denyAll() {
        return ignored -> CompletableFuture.completedStage(GitHubCopilotPermissionResponse.deny());
    }
}
