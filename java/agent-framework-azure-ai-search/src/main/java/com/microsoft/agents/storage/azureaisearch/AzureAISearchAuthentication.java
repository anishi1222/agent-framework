// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.util.Objects;

/** Configures either recommended Azure RBAC authentication or redacted query-key authentication. */
public final class AzureAISearchAuthentication {
    private final Kind kind;

    private final AzureAuthenticationProvider provider;

    private final AzureAISearchApiKey apiKey;

    private AzureAISearchAuthentication(Kind kind, AzureAuthenticationProvider provider, AzureAISearchApiKey apiKey) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.provider = provider;
        this.apiKey = apiKey;
    }

    /**
     * Creates recommended keyless Azure RBAC authentication.
     *
     * @param provider framework-owned Azure token provider
     * @return RBAC authentication
     */
    public static AzureAISearchAuthentication rbac(AzureAuthenticationProvider provider) {
        return new AzureAISearchAuthentication(Kind.RBAC, Objects.requireNonNull(provider, "provider"), null);
    }

    /**
     * Creates API-key authentication.
     *
     * @param apiKey redacting API key
     * @return key authentication
     */
    public static AzureAISearchAuthentication apiKey(AzureAISearchApiKey apiKey) {
        return new AzureAISearchAuthentication(Kind.API_KEY, null, Objects.requireNonNull(apiKey, "apiKey"));
    }

    /**
     * Returns the configured authentication kind.
     *
     * @return authentication kind
     */
    public Kind kind() {
        return kind;
    }

    AzureAuthenticationProvider provider() {
        return provider;
    }

    AzureAISearchApiKey apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "AzureAISearchAuthentication[kind=" + kind + ", credential=[REDACTED]]";
    }

    /** Supported Azure AI Search authentication kinds. */
    public enum Kind {
        /** Microsoft Entra ID and Azure role-based access control. */
        RBAC,

        /** Azure AI Search query or admin API key. */
        API_KEY
    }
}
