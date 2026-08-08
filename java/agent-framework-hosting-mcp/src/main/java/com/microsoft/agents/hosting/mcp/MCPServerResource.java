// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPResourceDescriptor;
import java.util.Objects;

/**
 * Defines one framework-owned resource exposed by an MCP server.
 *
 * @param descriptor resource metadata
 * @param handler resource handler
 */
public record MCPServerResource(MCPResourceDescriptor descriptor, MCPResourceHandler handler) {
    /** Creates an immutable hosted resource. */
    public MCPServerResource {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
    }
}
