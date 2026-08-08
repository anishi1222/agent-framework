// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.Objects;

/**
 * Represents an in-band form or out-of-band URL elicitation request.
 */
public sealed interface MCPElicitationRequest permits MCPElicitationRequest.Form, MCPElicitationRequest.Url {
    /**
     * Returns the user-facing request message.
     *
     * @return message
     */
    String message();

    /**
     * Represents form-mode elicitation.
     *
     * @param message user-facing message
     * @param requestedSchema restricted JSON-shaped response schema
     */
    record Form(String message, StateValue.ObjectValue requestedSchema) implements MCPElicitationRequest {
        /** Creates an immutable form request. */
        public Form {
            message = MCPValidation.nonBlank(message, "message");
            Objects.requireNonNull(requestedSchema, "requestedSchema");
        }
    }

    /**
     * Represents URL-mode elicitation.
     *
     * @param message user-facing message
     * @param url absolute interaction URL
     * @param elicitationId stable elicitation identifier
     */
    record Url(String message, URI url, String elicitationId) implements MCPElicitationRequest {
        /** Creates an immutable URL request. */
        public Url {
            message = MCPValidation.nonBlank(message, "message");
            Objects.requireNonNull(url, "url");
            if (!url.isAbsolute() || !"https".equalsIgnoreCase(url.getScheme())) {
                throw new com.microsoft.agents.core.ValidationException("elicitation URL must be absolute HTTPS.");
            }
            elicitationId = MCPValidation.nonBlank(elicitationId, "elicitationId");
        }
    }
}
