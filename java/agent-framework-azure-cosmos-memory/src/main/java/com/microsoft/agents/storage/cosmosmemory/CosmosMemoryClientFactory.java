// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.DirectConnectionConfig;
import com.azure.cosmos.ThrottlingRetryOptions;
import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.storage.cosmos.CosmosAuthentication;
import com.microsoft.agents.storage.cosmos.CosmosClientOptions;
import com.microsoft.agents.storage.cosmos.CosmosConnectionMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import reactor.core.publisher.Mono;

final class CosmosMemoryClientFactory {
    private CosmosMemoryClientFactory() {}

    static CosmosAsyncClient create(CosmosClientOptions options) {
        ThrottlingRetryOptions retry = new ThrottlingRetryOptions()
                .setMaxRetryAttemptsOnThrottledRequests(options.retryOptions().maxThrottlingRetryAttempts())
                .setMaxRetryWaitTime(options.retryOptions().maxThrottlingRetryWait());
        CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(options.endpoint().toString())
                .consistencyLevel(ConsistencyLevel.SESSION)
                .contentResponseOnWriteEnabled(false)
                .customItemSerializer(new CosmosMemoryItemSerializer())
                .throttlingRetryOptions(retry)
                .userAgentSuffix(options.userAgentSuffix())
                .preferredRegions(options.preferredRegions())
                .endpointDiscoveryEnabled(true);
        if (options.authentication().kind() == CosmosAuthentication.Kind.RBAC) {
            builder.credential(
                    tokenCredential(options.authentication().rbacProvider().orElseThrow()));
        } else {
            builder.key(options.authentication().accountKey().orElseThrow().secretValue());
        }
        if (options.connectionMode() == CosmosConnectionMode.GATEWAY) {
            builder.gatewayMode();
        } else {
            builder.directMode(new DirectConnectionConfig()
                    .setConnectionEndpointRediscoveryEnabled(true)
                    .setConnectTimeout(options.retryOptions().operationTimeout())
                    .setNetworkRequestTimeout(options.retryOptions().operationTimeout()));
        }
        return builder.buildAsyncClient();
    }

    private static TokenCredential tokenCredential(AzureAuthenticationProvider authentication) {
        return context -> Mono.fromCompletionStage(authentication.getTokenAsync(
                        new AzureTokenRequest(context.getScopes(), context.getTenantId()),
                        new DefaultRunCancellation()))
                .map(token ->
                        new AccessToken(token.token(), OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC)));
    }
}
