// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureAuthenticationProviders;
import java.util.Optional;

/** Selects framework-owned RBAC or explicitly wrapped account-key authentication. */
public sealed interface CosmosAuthentication permits CosmosRbacAuthentication, CosmosKeyAuthentication {
    /** Identifies the configured authentication mechanism. */
    enum Kind {
        /** Azure token authentication and Cosmos data-plane RBAC. */
        RBAC,
        /** Explicit account-key authentication. */
        ACCOUNT_KEY
    }

    /**
     * Creates preferred Azure RBAC authentication.
     *
     * @param provider framework-owned token provider
     * @return authentication configuration
     */
    static CosmosAuthentication rbac(AzureAuthenticationProvider provider) {
        return new CosmosRbacAuthentication(CosmosValidation.requireNonNull(provider, "provider"));
    }

    /**
     * Creates the production-constrained DefaultAzureCredential bridge.
     *
     * <p>The credential chain requires {@code AZURE_TOKEN_CREDENTIALS}; managed identity is selected
     * through that Azure Identity setting in hosted production environments.
     *
     * @return preferred RBAC authentication
     */
    static CosmosAuthentication productionDefaultCredential() {
        return rbac(AzureAuthenticationProviders.productionDefaultCredential());
    }

    /**
     * Creates system-assigned managed-identity authentication.
     *
     * @return preferred RBAC authentication
     */
    static CosmosAuthentication managedIdentity() {
        return rbac(AzureAuthenticationProviders.managedIdentity());
    }

    /**
     * Creates account-key authentication for emulator or explicit compatibility scenarios.
     *
     * @param key redacting account-key wrapper
     * @return authentication configuration
     */
    static CosmosAuthentication accountKey(CosmosAccountKey key) {
        return new CosmosKeyAuthentication(CosmosValidation.requireNonNull(key, "key"));
    }

    /**
     * Returns the authentication kind.
     *
     * @return authentication kind
     */
    Kind kind();

    /**
     * Returns the RBAC provider when configured.
     *
     * @return optional framework token provider
     */
    Optional<AzureAuthenticationProvider> rbacProvider();

    /**
     * Returns the redacting account-key wrapper when configured.
     *
     * @return optional key wrapper
     */
    Optional<CosmosAccountKey> accountKey();
}
