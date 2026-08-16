// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Obtains application-mediated input without reading a process console.
 */
@FunctionalInterface
public interface GitHubCopilotUserInputHandler {
    /**
     * Handles one input request.
     *
     * @param request input request
     * @return answer stage
     */
    CompletionStage<GitHubCopilotUserInputResponse> handleAsync(GitHubCopilotUserInputRequest request);

    /**
     * Returns a handler that declines all requests.
     *
     * @return declining handler
     */
    static GitHubCopilotUserInputHandler declineAll() {
        return ignored -> CompletableFuture.completedStage(GitHubCopilotUserInputResponse.cancelledResponse());
    }
}
