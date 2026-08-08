// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.concurrent.CompletionStage;

/**
 * Handles security-approved server-initiated MCP sampling requests.
 */
@FunctionalInterface
public interface MCPSamplingHandler {
    /**
     * Produces a sampling response asynchronously.
     *
     * @param request bounded framework-owned request
     * @return non-null completion stage
     */
    CompletionStage<MCPSamplingResult> sampleAsync(MCPSamplingRequest request);
}
