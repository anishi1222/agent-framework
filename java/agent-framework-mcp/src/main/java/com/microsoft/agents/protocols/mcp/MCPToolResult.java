// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Represents one terminal MCP tool result.
 *
 * @param content immutable rich content
 * @param error whether the server reported a tool-level error
 * @param structuredContent optional JSON-shaped structured output
 * @param metadata immutable MCP metadata
 */
public record MCPToolResult(
        List<MCPContent> content, boolean error, StateValue structuredContent, Map<String, StateValue> metadata) {
    /** Creates an immutable terminal tool result. */
    public MCPToolResult {
        content = MCPValidation.copyList(content, "content");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }

    /**
     * Returns concatenated text blocks for diagnostics.
     *
     * @return joined text, possibly empty
     */
    public String text() {
        return content.stream()
                .filter(MCPContent.Text.class::isInstance)
                .map(MCPContent.Text.class::cast)
                .map(MCPContent.Text::text)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
}
