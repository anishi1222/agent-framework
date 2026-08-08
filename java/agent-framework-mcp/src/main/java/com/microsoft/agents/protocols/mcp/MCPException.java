// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.AgentFrameworkException;

/**
 * Represents a sanitized MCP lifecycle, transport, or conversion failure.
 */
public class MCPException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an MCP failure.
     *
     * @param message sanitized actionable message
     */
    public MCPException(String message) {
        super(message);
    }

    /**
     * Creates an MCP failure with an internal cause.
     *
     * @param message sanitized actionable message
     * @param cause internal cause
     */
    public MCPException(String message, Throwable cause) {
        super(message, cause);
    }
}
