// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

/**
 * Binds history to an explicit tenant, isolation boundary, and agent namespace.
 *
 * @param tenantId stable tenant identifier
 * @param isolationId stable principal or host isolation identifier
 * @param agentId stable agent identifier
 */
public record ValkeyPartitionContext(String tenantId, String isolationId, String agentId) {
    /** Creates a validated explicit partition context. */
    public ValkeyPartitionContext {
        tenantId = ValkeyValidation.boundedIdentifier(tenantId, "tenantId", 4096);
        isolationId = ValkeyValidation.boundedIdentifier(isolationId, "isolationId", 4096);
        agentId = ValkeyValidation.boundedIdentifier(agentId, "agentId", 4096);
    }

    @Override
    public String toString() {
        return "ValkeyPartitionContext[tenantId=REDACTED, isolationId=REDACTED, agentId=REDACTED]";
    }
}
