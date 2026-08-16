// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.protocols.mcp.MCPPromptResult;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Resolves one hosted MCP prompt.
 */
@FunctionalInterface
public interface MCPPromptHandler {
    /**
     * Resolves a prompt asynchronously.
     *
     * @param arguments immutable string arguments
     * @return prompt-result stage
     */
    CompletionStage<MCPPromptResult> getAsync(Map<String, String> arguments);
}
