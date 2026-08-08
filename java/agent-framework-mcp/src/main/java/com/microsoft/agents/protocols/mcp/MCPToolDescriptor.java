// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Describes one tool advertised by an MCP server.
 *
 * @param name exact remote tool name
 * @param title optional display title
 * @param description description, possibly empty
 * @param inputSchema exact JSON-shaped input schema
 * @param outputSchema exact JSON-shaped output schema, or {@code null}
 * @param metadata immutable MCP metadata
 */
public record MCPToolDescriptor(
        String name,
        String title,
        String description,
        StateValue.ObjectValue inputSchema,
        StateValue.ObjectValue outputSchema,
        Map<String, StateValue> metadata) {
    /** Creates an immutable tool descriptor. */
    public MCPToolDescriptor {
        name = MCPValidation.nonBlank(name, "name");
        title = MCPValidation.optionalNonBlank(title, "title");
        description = description == null ? "" : description;
        java.util.Objects.requireNonNull(inputSchema, "inputSchema");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
