// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

/**
 * Identifies one memory record inside an explicit tenant scope.
 *
 * @param scope tenant and application scope
 * @param memoryId stable record identifier
 */
public record MemoryKey(MemoryScope scope, String memoryId) {
    /** Creates a validated memory key. */
    public MemoryKey {
        scope = MemoryValidation.requireNonNull(scope, "scope");
        memoryId = MemoryValidation.requireNonBlank(memoryId, "memoryId");
    }
}
