// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.concurrent.CompletionStage;

/**
 * Handles one official SDK session hook without exposing SDK types.
 */
@FunctionalInterface
public interface GitHubCopilotHook {
    /**
     * Handles an SDK-originated hook request.
     *
     * @param request hook request
     * @return explicit hook result stage
     */
    CompletionStage<GitHubCopilotHookResult> handleAsync(GitHubCopilotHookRequest request);
}
