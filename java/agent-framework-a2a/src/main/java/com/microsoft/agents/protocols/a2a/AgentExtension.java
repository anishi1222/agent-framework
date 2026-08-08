// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.Map;

/**
 * Declares an additive A2A extension.
 *
 * @param uri globally unique extension URI
 * @param description optional description
 * @param required whether peers must understand the extension
 * @param params immutable JSON-shaped extension parameters
 */
public record AgentExtension(URI uri, String description, boolean required, Map<String, StateValue> params) {
    /** Creates an immutable extension declaration. */
    public AgentExtension {
        uri = A2AValidation.absoluteUri(uri, "uri");
        description = A2AValidation.optionalNonBlank(description, "description");
        params = A2AValidation.metadata(params, "params");
    }
}
