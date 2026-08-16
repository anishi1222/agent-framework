// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.Map;

/**
 * Represents one client-provided AG-UI tool declaration.
 *
 * @param name tool name
 * @param description tool description
 * @param parameters JSON Schema for arguments
 * @param metadata arbitrary protocol metadata
 */
public record AGUITool(String name, String description, StateValue parameters, Map<String, StateValue> metadata) {
    /** Creates a validated immutable tool declaration. */
    public AGUITool {
        name = AGUIValidation.nonBlank(name, "name");
        description = AGUIValidation.nonBlank(description, "description");
        parameters = AGUIValidation.state(parameters, "parameters");
        metadata = AGUIValidation.map(metadata, "metadata");
    }

    /**
     * Creates a tool without metadata.
     *
     * @param name tool name
     * @param description tool description
     * @param parameters JSON Schema
     */
    public AGUITool(String name, String description, StateValue parameters) {
        this(name, description, parameters, Map.of());
    }
}
