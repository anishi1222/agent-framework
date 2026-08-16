// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Represents a resolved MCP prompt.
 *
 * @param description description, possibly empty
 * @param messages immutable prompt messages
 * @param metadata immutable MCP metadata
 */
public record MCPPromptResult(String description, List<MCPPromptMessage> messages, Map<String, StateValue> metadata) {
    /** Creates an immutable prompt result. */
    public MCPPromptResult {
        description = description == null ? "" : description;
        messages = MCPValidation.copyList(messages, "messages");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
