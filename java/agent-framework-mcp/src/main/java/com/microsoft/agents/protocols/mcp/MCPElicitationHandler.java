// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.concurrent.CompletionStage;

/**
 * Handles security-approved server-initiated MCP elicitation requests.
 */
@FunctionalInterface
public interface MCPElicitationHandler {
    /**
     * Resolves an elicitation asynchronously.
     *
     * @param request bounded framework-owned request
     * @return non-null completion stage
     */
    CompletionStage<MCPElicitationResult> elicitAsync(MCPElicitationRequest request);
}
