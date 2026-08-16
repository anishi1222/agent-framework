// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

/**
 * Identifies the mandatory tenant and application scope for memory operations.
 *
 * @param tenantId stable tenant identifier
 * @param scopeId stable user, principal, or application scope identifier
 */
public record MemoryScope(String tenantId, String scopeId) {
    /** Creates a validated explicit memory scope. */
    public MemoryScope {
        tenantId = MemoryValidation.requireNonBlank(tenantId, "tenantId");
        scopeId = MemoryValidation.requireNonBlank(scopeId, "scopeId");
    }
}
