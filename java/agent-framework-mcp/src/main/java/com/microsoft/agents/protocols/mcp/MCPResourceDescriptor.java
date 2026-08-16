// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one resource advertised by an MCP server.
 *
 * @param uri absolute resource URI
 * @param name resource name
 * @param title optional title
 * @param description description, possibly empty
 * @param mediaType optional media type
 * @param size optional non-negative size
 * @param metadata immutable MCP metadata
 */
public record MCPResourceDescriptor(
        URI uri,
        String name,
        String title,
        String description,
        String mediaType,
        Long size,
        Map<String, StateValue> metadata) {
    /** Creates an immutable resource descriptor. */
    public MCPResourceDescriptor {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new com.microsoft.agents.core.ValidationException("resource uri must be absolute.");
        }
        name = MCPValidation.nonBlank(name, "name");
        title = MCPValidation.optionalNonBlank(title, "title");
        description = description == null ? "" : description;
        mediaType = MCPValidation.optionalNonBlank(mediaType, "mediaType");
        if (size != null && size < 0) {
            throw new com.microsoft.agents.core.ValidationException("size must be non-negative.");
        }
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
