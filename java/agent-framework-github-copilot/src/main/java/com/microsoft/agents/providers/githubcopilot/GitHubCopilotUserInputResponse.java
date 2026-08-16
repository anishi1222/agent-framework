// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Contains an explicit user-input answer or cancellation.
 *
 * @param answer optional answer
 * @param freeform whether the answer was not one of the offered choices
 * @param cancelled whether input was declined
 */
public record GitHubCopilotUserInputResponse(String answer, boolean freeform, boolean cancelled) {
    /** Creates a validated response. */
    public GitHubCopilotUserInputResponse {
        if (!cancelled && (answer == null || answer.isBlank())) {
            throw new IllegalArgumentException("answer must not be blank unless cancelled.");
        }
        if (cancelled) {
            answer = "";
            freeform = false;
        }
    }

    /**
     * Returns a declined-input response.
     *
     * @return cancelled response
     */
    public static GitHubCopilotUserInputResponse cancelledResponse() {
        return new GitHubCopilotUserInputResponse("", false, true);
    }
}
