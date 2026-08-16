// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

/**
 * Selects a caller-registered chat client and provider-neutral model configuration.
 *
 * @param id model identifier passed to the selected chat client
 * @param provider optional chat-client registry key
 * @param apiType optional provider API variant appended to the registry key
 * @param options provider-neutral generation options
 */
public record PromptModelDefinition(String id, String provider, String apiType, PromptModelOptions options) {
    /** Creates a validated immutable model definition. */
    public PromptModelDefinition {
        id = AgentDefinitionValidation.requireNonBlank(id, "model.id");
        provider = AgentDefinitionValidation.optionalNonBlank(provider, "model.provider");
        apiType = AgentDefinitionValidation.optionalNonBlank(apiType, "model.apiType");
        options = options == null ? PromptModelOptions.empty() : options;
    }
}
