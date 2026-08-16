// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.AzureIdentityEnvVars;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;

/** Creates framework-owned authentication providers backed by Azure Identity. */
public final class AzureAuthenticationProviders {
    private AzureAuthenticationProviders() {}

    /**
     * Creates a development-friendly default credential chain.
     *
     * <p>Production applications should prefer {@link #productionDefaultCredential()} or a managed
     * identity factory so credential probing is deterministic.
     *
     * @return authentication provider
     */
    public static AzureAuthenticationProvider defaultCredential() {
        return wrap(new DefaultAzureCredentialBuilder().build());
    }

    /**
     * Creates a production default credential that requires {@code AZURE_TOKEN_CREDENTIALS}.
     *
     * @return authentication provider
     */
    public static AzureAuthenticationProvider productionDefaultCredential() {
        return wrap(new DefaultAzureCredentialBuilder()
                .requireEnvVars(AzureIdentityEnvVars.AZURE_TOKEN_CREDENTIALS)
                .build());
    }

    /**
     * Creates a system-assigned managed identity provider.
     *
     * @return authentication provider
     */
    public static AzureAuthenticationProvider managedIdentity() {
        return wrap(new ManagedIdentityCredentialBuilder().build());
    }

    /**
     * Creates a user-assigned managed identity provider.
     *
     * @param clientId managed identity client identifier
     * @return authentication provider
     */
    public static AzureAuthenticationProvider managedIdentity(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank.");
        }
        return wrap(new ManagedIdentityCredentialBuilder().clientId(clientId).build());
    }

    private static AzureAuthenticationProvider wrap(TokenCredential credential) {
        return new AzureIdentityAuthenticationProvider(credential);
    }
}
