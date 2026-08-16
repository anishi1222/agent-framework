// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Selects who launches and owns the Copilot CLI process.
 */
public enum GitHubCopilotCliLaunchMode {
    /**
     * Lets the official Java SDK launch, connect to, and stop the CLI.
     *
     * <p>This is the default and supports the SDK's documented stdio lifecycle.
     */
    SDK_MANAGED,

    /**
     * Uses the framework's optional hardened external launcher and connects the official SDK to it.
     *
     * <p>This mode exists only for applications that require bounded process output and explicit
     * descendant-process termination. The official SDK still owns all RPC, handshake, session, and
     * event semantics.
     */
    HARDENED_EXTERNAL
}
