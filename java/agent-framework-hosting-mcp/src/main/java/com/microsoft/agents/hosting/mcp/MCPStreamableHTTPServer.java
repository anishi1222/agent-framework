// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import java.net.URI;

/**
 * Represents an owned embedded Streamable HTTP/SSE MCP server.
 */
public interface MCPStreamableHTTPServer extends MCPServerHandle {
    /**
     * Returns the bound endpoint URI.
     *
     * <p>The URI uses HTTP for the loopback listener or trusted proxy hop. Remote clients must use
     * the externally configured HTTPS URI.
     *
     * @return bound endpoint
     */
    URI endpoint();
}
