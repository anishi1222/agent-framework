// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;

/**
 * Contains one correlated custom-tool invocation.
 *
 * @param sessionId session identity
 * @param callId tool-call identity
 * @param name tool name
 * @param arguments strict JSON arguments
 */
public record GitHubCopilotToolCall(String sessionId, String callId, String name, StateValue.ObjectValue arguments) {
    /** Creates a validated invocation. */
    public GitHubCopilotToolCall {
        if (sessionId == null
                || sessionId.isBlank()
                || callId == null
                || callId.isBlank()
                || name == null
                || name.isBlank()
                || arguments == null) {
            throw new IllegalArgumentException("sessionId, callId, name, and arguments are required.");
        }
    }
}
