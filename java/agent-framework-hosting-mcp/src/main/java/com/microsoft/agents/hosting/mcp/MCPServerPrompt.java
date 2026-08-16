// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPPromptArgument;
import java.util.List;
import java.util.Objects;

/**
 * Defines one framework-owned prompt exposed by an MCP server.
 *
 * @param name stable prompt name
 * @param description description, possibly empty
 * @param arguments immutable prompt arguments
 * @param handler prompt handler
 */
public record MCPServerPrompt(
        String name, String description, List<MCPPromptArgument> arguments, MCPPromptHandler handler) {
    /** Creates an immutable hosted prompt. */
    public MCPServerPrompt {
        name = HostingMCPValidation.nonBlank(name, "name");
        description = description == null ? "" : description;
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(handler, "handler");
    }
}
