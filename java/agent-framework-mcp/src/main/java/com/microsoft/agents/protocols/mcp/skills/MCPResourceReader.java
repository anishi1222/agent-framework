// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Reads MCP resources for skill discovery and lazy resource access. */
@FunctionalInterface
public interface MCPResourceReader {
    /**
     * Reads one absolute resource URI.
     *
     * @param uri resource URI
     * @param cancellation cancellation signal
     * @return MCP resource result stage
     */
    CompletionStage<MCPReadResourceResult> readAsync(URI uri, RunCancellation cancellation);
}
