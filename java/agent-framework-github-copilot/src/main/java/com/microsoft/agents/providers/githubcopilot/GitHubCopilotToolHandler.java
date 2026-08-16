// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/**
 * Executes a caller-declared Copilot custom tool.
 */
@FunctionalInterface
public interface GitHubCopilotToolHandler {
    /**
     * Executes one correlated call.
     *
     * @param call tool call
     * @return strict JSON result stage
     */
    CompletionStage<StateValue> invokeAsync(GitHubCopilotToolCall call);
}
