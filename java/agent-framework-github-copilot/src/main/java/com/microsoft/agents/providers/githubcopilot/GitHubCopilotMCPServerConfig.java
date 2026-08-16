// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.time.Duration;
import java.util.List;

/**
 * Describes a caller-declared MCP server made available to a Copilot session.
 */
public sealed interface GitHubCopilotMCPServerConfig
        permits GitHubCopilotMCPStdioServerConfig, GitHubCopilotMCPHttpServerConfig {
    /**
     * Returns the explicitly allowed tool names.
     *
     * @return immutable tool names
     */
    List<String> tools();

    /**
     * Returns the tool-call timeout.
     *
     * @return timeout
     */
    Duration timeout();
}
