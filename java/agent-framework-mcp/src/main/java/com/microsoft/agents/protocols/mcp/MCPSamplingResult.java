// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.Objects;

/**
 * Represents the client response to an MCP sampling request.
 *
 * @param role response role
 * @param content response content
 * @param model model identifier
 * @param stopReason optional stop reason
 */
public record MCPSamplingResult(MCPRole role, MCPContent content, String model, StopReason stopReason) {
    /** Creates an immutable sampling result. */
    public MCPSamplingResult {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        model = MCPValidation.nonBlank(model, "model");
    }

    /**
     * Defines standardized sampling stop reasons.
     */
    public enum StopReason {
        /** The model ended its turn. */
        END_TURN,
        /** A configured stop sequence matched. */
        STOP_SEQUENCE,
        /** The token limit was reached. */
        MAX_TOKENS,
        /** The peer reported an unknown reason. */
        UNKNOWN
    }
}
