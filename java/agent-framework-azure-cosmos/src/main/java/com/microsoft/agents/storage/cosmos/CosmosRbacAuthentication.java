// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.util.Optional;

record CosmosRbacAuthentication(AzureAuthenticationProvider provider) implements CosmosAuthentication {
    @Override
    public Kind kind() {
        return Kind.RBAC;
    }

    @Override
    public Optional<AzureAuthenticationProvider> rbacProvider() {
        return Optional.of(provider);
    }

    @Override
    public Optional<CosmosAccountKey> accountKey() {
        return Optional.empty();
    }
}
