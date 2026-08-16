// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import java.net.URI;
import java.util.concurrent.CompletionStage;

/**
 * Reads one hosted MCP resource.
 */
@FunctionalInterface
public interface MCPResourceHandler {
    /**
     * Reads a resource asynchronously.
     *
     * @param uri exact requested URI
     * @return resource-result stage
     */
    CompletionStage<MCPReadResourceResult> readAsync(URI uri);
}
