// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Describes a permission request correlated to a session and optional tool call.
 *
 * @param sessionId session identity
 * @param kind upstream permission kind
 * @param toolCallId optional tool-call identity
 * @param managedApprovalRequired whether enterprise policy requires a human decision
 * @param metadata bounded framework-owned extension data
 */
public record GitHubCopilotPermissionRequest(
        String sessionId,
        String kind,
        String toolCallId,
        boolean managedApprovalRequired,
        Map<String, StateValue> metadata) {
    /** Creates and defensively copies a request. */
    public GitHubCopilotPermissionRequest {
        sessionId = requireNonBlank(sessionId, "sessionId");
        kind = requireNonBlank(kind, "kind");
        toolCallId = toolCallId == null || toolCallId.isBlank() ? null : toolCallId;
        metadata = Map.copyOf(metadata);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
