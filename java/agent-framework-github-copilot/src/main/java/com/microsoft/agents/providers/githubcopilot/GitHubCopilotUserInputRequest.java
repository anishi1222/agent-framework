// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.util.List;

/**
 * Describes an explicit request for application-mediated user input.
 *
 * @param sessionId session identity
 * @param question user-facing question
 * @param choices immutable choices
 * @param allowFreeform whether a free-form answer is accepted
 */
public record GitHubCopilotUserInputRequest(
        String sessionId, String question, List<String> choices, boolean allowFreeform) {
    /** Creates and defensively copies a request. */
    public GitHubCopilotUserInputRequest {
        if (sessionId == null || sessionId.isBlank() || question == null || question.isBlank()) {
            throw new IllegalArgumentException("sessionId and question must not be blank.");
        }
        choices = List.copyOf(choices);
    }
}
