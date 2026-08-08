// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Describes one string argument accepted by an MCP prompt.
 *
 * @param name argument name
 * @param description description, possibly empty
 * @param required whether the argument is required
 */
public record MCPPromptArgument(String name, String description, boolean required) {
    /** Creates an immutable prompt argument. */
    public MCPPromptArgument {
        name = MCPValidation.nonBlank(name, "name");
        description = description == null ? "" : description;
    }
}
