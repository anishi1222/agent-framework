// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.util.Optional;

record CosmosKeyAuthentication(CosmosAccountKey key) implements CosmosAuthentication {
    @Override
    public Kind kind() {
        return Kind.ACCOUNT_KEY;
    }

    @Override
    public Optional<AzureAuthenticationProvider> rbacProvider() {
        return Optional.empty();
    }

    @Override
    public Optional<CosmosAccountKey> accountKey() {
        return Optional.of(key);
    }

    @Override
    public String toString() {
        return "CosmosAuthentication.accountKey([REDACTED])";
    }
}
