// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Declares a custom tool with a preserved JSON Schema and mandatory permission boundary.
 *
 * @param name unique tool name
 * @param description model-facing description
 * @param parameters JSON Schema object
 * @param handler invocation handler
 */
public record GitHubCopilotTool(
        String name, String description, StateValue.ObjectValue parameters, GitHubCopilotToolHandler handler) {
    /** Creates a validated tool declaration. */
    public GitHubCopilotTool {
        name = requireNonBlank(name, "name");
        description = requireNonBlank(description, "description");
        parameters = Objects.requireNonNull(parameters, "parameters");
        handler = Objects.requireNonNull(handler, "handler");
        StateValue type = parameters.values().get("type");
        if (!(type instanceof StateValue.StringValue string) || !"object".equals(string.value())) {
            throw new IllegalArgumentException("parameters must be a JSON Schema object with type 'object'.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
