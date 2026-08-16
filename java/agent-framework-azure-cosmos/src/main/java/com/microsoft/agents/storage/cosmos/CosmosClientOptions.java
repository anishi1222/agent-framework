// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/**
 * Configures one framework-owned resilient Cosmos SDK client.
 *
 * @param endpoint exact account endpoint
 * @param authentication RBAC or wrapped key authentication
 * @param retryOptions bounded retry and deadline policy
 * @param connectionMode direct or gateway connectivity
 * @param userAgentSuffix non-blank application suffix
 * @param preferredRegions ordered Cosmos account regions used for SDK failover
 */
public record CosmosClientOptions(
        CosmosEndpoint endpoint,
        CosmosAuthentication authentication,
        CosmosRetryOptions retryOptions,
        CosmosConnectionMode connectionMode,
        String userAgentSuffix,
        java.util.List<String> preferredRegions) {
    /** Creates validated immutable client options. */
    public CosmosClientOptions {
        endpoint = CosmosValidation.requireNonNull(endpoint, "endpoint");
        authentication = CosmosValidation.requireNonNull(authentication, "authentication");
        retryOptions = retryOptions == null ? CosmosRetryOptions.defaults() : retryOptions;
        connectionMode = connectionMode == null ? CosmosConnectionMode.DIRECT : connectionMode;
        userAgentSuffix = CosmosValidation.requireNonBlank(userAgentSuffix, "userAgentSuffix");
        preferredRegions = java.util.List.copyOf(CosmosValidation.requireNonNull(preferredRegions, "preferredRegions"));
        if (preferredRegions.size() > 10) {
            throw new com.microsoft.agents.core.ValidationException(
                    "preferredRegions must contain at most 10 regions.");
        }
        preferredRegions.forEach(region -> CosmosValidation.requireNonBlank(region, "preferred region"));
    }

    /**
     * Creates client options without an explicit preferred-region order.
     *
     * @param endpoint exact account endpoint
     * @param authentication authentication configuration
     * @param retryOptions retry and deadline options
     * @param connectionMode connectivity mode
     * @param userAgentSuffix application user-agent suffix
     */
    public CosmosClientOptions(
            CosmosEndpoint endpoint,
            CosmosAuthentication authentication,
            CosmosRetryOptions retryOptions,
            CosmosConnectionMode connectionMode,
            String userAgentSuffix) {
        this(endpoint, authentication, retryOptions, connectionMode, userAgentSuffix, java.util.List.of());
    }
}
