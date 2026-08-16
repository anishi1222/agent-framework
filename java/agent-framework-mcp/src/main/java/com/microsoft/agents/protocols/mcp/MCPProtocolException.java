// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

/**
 * Represents a sanitized JSON-RPC error returned by an MCP peer.
 */
public final class MCPProtocolException extends MCPException {
    private static final long serialVersionUID = 1L;

    private final int code;

    private final String operation;

    /**
     * Creates a protocol failure.
     *
     * @param code JSON-RPC error code
     * @param operation failed protocol operation
     * @param message sanitized actionable message
     * @param cause internal cause
     */
    public MCPProtocolException(int code, String operation, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.operation = MCPValidation.nonBlank(operation, "operation");
    }

    /**
     * Returns the JSON-RPC error code.
     *
     * @return protocol code
     */
    public int code() {
        return code;
    }

    /**
     * Returns the failed protocol operation.
     *
     * @return operation
     */
    public String operation() {
        return operation;
    }
}
