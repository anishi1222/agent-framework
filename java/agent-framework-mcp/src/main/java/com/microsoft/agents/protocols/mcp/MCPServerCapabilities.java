// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Captures capabilities negotiated with an MCP server.
 *
 * @param tools tool discovery and invocation
 * @param toolListChanged tool-list notifications
 * @param prompts prompt discovery
 * @param promptListChanged prompt-list notifications
 * @param resources resource discovery and reads
 * @param resourceSubscriptions resource subscriptions
 * @param resourceListChanged resource-list notifications
 * @param logging logging notifications
 * @param completions argument completion
 */
public record MCPServerCapabilities(
        boolean tools,
        boolean toolListChanged,
        boolean prompts,
        boolean promptListChanged,
        boolean resources,
        boolean resourceSubscriptions,
        boolean resourceListChanged,
        boolean logging,
        boolean completions) {}
