// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.net.URI;
import java.util.Map;

/**
 * Configures a documented OpenAI-compatible BYOK provider for one Copilot session.
 *
 * @param type upstream provider type
 * @param wireApi documented wire API name
 * @param baseUri HTTPS provider base URI
 * @param apiKey optional API key
 * @param bearerToken optional bearer token
 * @param headers immutable non-secret headers
 * @param modelId model identifier exposed to Copilot
 * @param wireModel provider wire-model identifier
 * @param maxPromptTokens optional positive prompt-token limit
 * @param maxOutputTokens optional positive output-token limit
 */
public record GitHubCopilotProviderConfig(
        String type,
        String wireApi,
        URI baseUri,
        GitHubCopilotSecret apiKey,
        GitHubCopilotSecret bearerToken,
        Map<String, String> headers,
        String modelId,
        String wireModel,
        Integer maxPromptTokens,
        Integer maxOutputTokens) {
    /** Creates and validates a BYOK configuration. */
    public GitHubCopilotProviderConfig {
        type = required(type, "type");
        wireApi = required(wireApi, "wireApi");
        baseUri = java.util.Objects.requireNonNull(baseUri, "baseUri").normalize();
        if (!baseUri.isAbsolute()
                || !"https".equalsIgnoreCase(baseUri.getScheme())
                || baseUri.getHost() == null
                || baseUri.getRawUserInfo() != null
                || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTPS URI without user info or fragment.");
        }
        if ((apiKey == null) == (bearerToken == null)) {
            throw new IllegalArgumentException("Exactly one of apiKey or bearerToken is required.");
        }
        headers = Map.copyOf(headers);
        headers.forEach((name, value) -> {
            if (name == null
                    || name.isBlank()
                    || name.equalsIgnoreCase("authorization")
                    || value == null
                    || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("headers contain an invalid or credential-bearing entry.");
            }
        });
        modelId = required(modelId, "modelId");
        wireModel = required(wireModel, "wireModel");
        positive(maxPromptTokens, "maxPromptTokens");
        positive(maxOutputTokens, "maxOutputTokens");
    }

    @Override
    public String toString() {
        return "GitHubCopilotProviderConfig{type='"
                + type
                + "', wireApi='"
                + wireApi
                + "', baseUri="
                + baseUri
                + ", apiKey="
                + (apiKey == null ? "<absent>" : "[REDACTED]")
                + ", bearerToken="
                + (bearerToken == null ? "<absent>" : "[REDACTED]")
                + ", headerNames="
                + headers.keySet()
                + ", modelId='"
                + modelId
                + "', wireModel='"
                + wireModel
                + "', maxPromptTokens="
                + maxPromptTokens
                + ", maxOutputTokens="
                + maxOutputTokens
                + '}';
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static void positive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }
}
