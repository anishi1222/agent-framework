// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Represents one explicit response to a Copilot permission request.
 */
public enum GitHubCopilotPermissionDecision {
    /** Denies the operation with caller-supplied feedback. */
    DENY,
    /** Allows only the correlated invocation. */
    APPROVE_ONCE,
    /** Reports that no authorized approver is available. */
    USER_NOT_AVAILABLE
}
