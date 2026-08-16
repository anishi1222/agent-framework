// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Represents contents returned by an MCP resource read.
 *
 * @param contents immutable resource contents
 * @param metadata immutable MCP metadata
 */
public record MCPReadResourceResult(List<MCPResourceContents> contents, Map<String, StateValue> metadata) {
    /** Creates an immutable resource result. */
    public MCPReadResourceResult {
        contents = MCPValidation.copyList(contents, "contents");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
