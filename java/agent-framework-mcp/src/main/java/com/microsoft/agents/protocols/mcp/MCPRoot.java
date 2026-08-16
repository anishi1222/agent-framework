// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.net.URI;
import java.util.Objects;

/**
 * Describes a client root exposed to an MCP server.
 *
 * @param uri absolute root URI
 * @param name optional display name
 */
public record MCPRoot(URI uri, String name) {
    /** Creates a validated root. */
    public MCPRoot {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new com.microsoft.agents.core.ValidationException("root uri must be absolute.");
        }
        name = MCPValidation.optionalNonBlank(name, "name");
    }

    /**
     * Creates an unnamed root.
     *
     * @param uri absolute URI
     */
    public MCPRoot(URI uri) {
        this(uri, null);
    }
}
