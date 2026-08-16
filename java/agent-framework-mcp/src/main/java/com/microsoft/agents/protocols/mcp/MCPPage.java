// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.List;

/**
 * Represents one bounded MCP cursor page.
 *
 * @param items immutable page items
 * @param nextCursor opaque cursor for the next page, or {@code null}
 * @param <T> item type
 */
public record MCPPage<T>(List<T> items, String nextCursor) {
    /** Creates an immutable page. */
    public MCPPage {
        items = MCPValidation.copyList(items, "items");
        nextCursor = MCPValidation.optionalNonBlank(nextCursor, "nextCursor");
    }

    /**
     * Reports whether another page is advertised.
     *
     * @return {@code true} when a non-blank cursor is present
     */
    public boolean hasMore() {
        return nextCursor != null;
    }
}
