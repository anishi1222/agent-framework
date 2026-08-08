// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import java.util.concurrent.CompletionStage;

/**
 * Represents one owned MCP server transport and its lifecycle.
 */
public interface MCPServerHandle extends AutoCloseable {
    /**
     * Reports whether the transport is accepting work.
     *
     * @return running state
     */
    boolean isRunning();

    /**
     * Closes the server and all framework-owned resources asynchronously.
     *
     * @return close stage
     */
    CompletionStage<Void> closeAsync();

    /**
     * Closes the server synchronously.
     */
    @Override
    void close();
}
