// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Contains an explicit permission decision and non-secret feedback.
 *
 * @param decision decision
 * @param feedback optional user-facing feedback
 */
public record GitHubCopilotPermissionResponse(GitHubCopilotPermissionDecision decision, String feedback) {
    /** Creates a validated response. */
    public GitHubCopilotPermissionResponse {
        if (decision == null) {
            throw new NullPointerException("decision");
        }
        feedback = feedback == null || feedback.isBlank() ? null : feedback;
    }

    /**
     * Returns a deny-by-default response.
     *
     * @return denied response
     */
    public static GitHubCopilotPermissionResponse deny() {
        return new GitHubCopilotPermissionResponse(
                GitHubCopilotPermissionDecision.DENY, "Permission was not explicitly approved.");
    }

    /**
     * Returns a one-invocation approval.
     *
     * @return one-time approval
     */
    public static GitHubCopilotPermissionResponse approveOnce() {
        return new GitHubCopilotPermissionResponse(GitHubCopilotPermissionDecision.APPROVE_ONCE, null);
    }
}
