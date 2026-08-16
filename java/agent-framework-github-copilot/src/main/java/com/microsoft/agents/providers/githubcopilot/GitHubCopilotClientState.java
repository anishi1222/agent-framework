// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Represents the lifecycle state of a {@link GitHubCopilotClient}.
 */
public enum GitHubCopilotClientState {
    /** The client has not started. */
    NEW,
    /** Startup and protocol negotiation are in progress. */
    STARTING,
    /** The protocol connection is ready. */
    RUNNING,
    /** Shutdown is in progress. */
    STOPPING,
    /** Shutdown completed. */
    STOPPED,
    /** Startup or transport failed. */
    FAILED
}
