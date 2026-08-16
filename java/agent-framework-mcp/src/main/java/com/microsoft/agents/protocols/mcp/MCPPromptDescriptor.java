// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Describes one prompt advertised by an MCP server.
 *
 * @param name exact remote prompt name
 * @param title optional title
 * @param description description, possibly empty
 * @param arguments immutable prompt arguments
 * @param metadata immutable MCP metadata
 */
public record MCPPromptDescriptor(
        String name,
        String title,
        String description,
        List<MCPPromptArgument> arguments,
        Map<String, StateValue> metadata) {
    /** Creates an immutable prompt descriptor. */
    public MCPPromptDescriptor {
        name = MCPValidation.nonBlank(name, "name");
        title = MCPValidation.optionalNonBlank(title, "title");
        description = description == null ? "" : description;
        arguments = MCPValidation.copyList(arguments, "arguments");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
