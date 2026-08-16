// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Selects the stable official SDK client mode without exposing SDK types.
 */
public enum GitHubCopilotClientMode {
    /** Uses normal Copilot CLI configuration and persistence. */
    COPILOT_CLI,

    /**
     * Starts from an empty configuration boundary.
     *
     * <p>The official SDK requires either an explicit Copilot home or an external CLI server in
     * this mode.
     */
    EMPTY
}
