// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Describes one URI-template resource advertised by an MCP server.
 *
 * @param uriTemplate RFC 6570 URI template
 * @param name template name
 * @param title optional title
 * @param description description, possibly empty
 * @param mediaType optional media type
 * @param metadata immutable MCP metadata
 */
public record MCPResourceTemplateDescriptor(
        String uriTemplate,
        String name,
        String title,
        String description,
        String mediaType,
        Map<String, StateValue> metadata) {
    /** Creates an immutable resource-template descriptor. */
    public MCPResourceTemplateDescriptor {
        uriTemplate = MCPValidation.nonBlank(uriTemplate, "uriTemplate");
        name = MCPValidation.nonBlank(name, "name");
        title = MCPValidation.optionalNonBlank(title, "title");
        description = description == null ? "" : description;
        mediaType = MCPValidation.optionalNonBlank(mediaType, "mediaType");
        metadata = MCPValidation.copyMap(metadata, "metadata");
    }
}
