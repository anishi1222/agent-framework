// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Represents negotiated MCP initialization information.
 *
 * @param protocolVersion negotiated protocol version
 * @param serverName server implementation name
 * @param serverVersion server implementation version
 * @param instructions server instructions, possibly empty
 * @param capabilities negotiated server capabilities
 * @param metadata immutable initialization metadata
 */
public record MCPInitialization(
        String protocolVersion,
        String serverName,
        String serverVersion,
        String instructions,
        MCPServerCapabilities capabilities,
        Map<String, StateValue> metadata) {
    /** Creates immutable initialization information. */
    public MCPInitialization {
        protocolVersion = MCPValidation.nonBlank(protocolVersion, "protocolVersion");
        serverName = MCPValidation.nonBlank(serverName, "serverName");
        serverVersion = MCPValidation.nonBlank(serverVersion, "serverVersion");
        instructions = instructions == null ? "" : instructions;
        java.util.Objects.requireNonNull(capabilities, "capabilities");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
