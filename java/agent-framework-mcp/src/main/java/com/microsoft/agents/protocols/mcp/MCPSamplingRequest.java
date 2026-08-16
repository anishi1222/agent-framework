// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Represents a bounded server-initiated MCP sampling request.
 *
 * @param messages immutable sampling messages
 * @param maxTokens requested maximum output tokens
 * @param systemPrompt optional system prompt
 * @param parameters remaining JSON-shaped request parameters
 */
public record MCPSamplingRequest(
        List<MCPPromptMessage> messages, int maxTokens, String systemPrompt, StateValue.ObjectValue parameters) {
    /** Creates an immutable sampling request. */
    public MCPSamplingRequest {
        messages = MCPValidation.copyList(messages, "messages");
        MCPValidation.positive(maxTokens, "maxTokens");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        java.util.Objects.requireNonNull(parameters, "parameters");
    }
}
