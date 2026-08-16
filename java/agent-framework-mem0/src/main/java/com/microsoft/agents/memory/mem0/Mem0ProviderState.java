// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import java.util.Objects;

/**
 * Carries the explicit Mem0 storage and retrieval scopes for one provider request.
 *
 * @param storageScope scope used by V3 add
 * @param searchScope scope used by V3 search
 */
public record Mem0ProviderState(Mem0Scope storageScope, Mem0Scope searchScope) {
    /** Creates and validates provider state. */
    public Mem0ProviderState {
        storageScope = Objects.requireNonNull(storageScope, "storageScope");
        searchScope = Objects.requireNonNull(searchScope, "searchScope");
    }

    /**
     * Uses the same scope for storage and retrieval.
     *
     * @param scope shared explicit scope
     */
    public Mem0ProviderState(Mem0Scope scope) {
        this(scope, scope);
    }
}
