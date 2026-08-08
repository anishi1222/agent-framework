// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.Objects;

/**
 * Represents one MCP prompt or sampling message.
 *
 * @param role message role
 * @param content rich message content
 */
public record MCPPromptMessage(MCPRole role, MCPContent content) {
    /** Creates an immutable prompt message. */
    public MCPPromptMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
