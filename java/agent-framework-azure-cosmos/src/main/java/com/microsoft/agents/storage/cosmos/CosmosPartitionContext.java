// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/**
 * Binds storage to an explicit tenant, isolation boundary, and agent namespace.
 *
 * @param tenantId stable tenant identifier
 * @param isolationId stable principal or host isolation identifier
 * @param agentId stable agent identifier
 */
public record CosmosPartitionContext(String tenantId, String isolationId, String agentId) {
    /** Creates a validated explicit partition context. */
    public CosmosPartitionContext {
        tenantId = CosmosValidation.requireNonBlank(tenantId, "tenantId");
        isolationId = CosmosValidation.requireNonBlank(isolationId, "isolationId");
        agentId = CosmosValidation.requireNonBlank(agentId, "agentId");
    }
}
