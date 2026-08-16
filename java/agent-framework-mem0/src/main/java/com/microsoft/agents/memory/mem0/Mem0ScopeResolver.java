// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.agents.ContextProviderRequest;

/**
 * Resolves trusted application-configured Mem0 scopes for one provider request.
 *
 * <p>The provider never derives identity fields from model output, message content, or run metadata.
 * Resolver implementations are trusted application code and must return explicit non-null state.
 */
@FunctionalInterface
public interface Mem0ScopeResolver {
    /**
     * Resolves storage and retrieval scopes.
     *
     * @param request current immutable provider request
     * @return explicit provider state
     */
    Mem0ProviderState resolve(ContextProviderRequest request);

    /**
     * Creates a resolver that always returns one immutable state value.
     *
     * @param state fixed state
     * @return fixed resolver
     */
    static Mem0ScopeResolver fixed(Mem0ProviderState state) {
        return ignored -> java.util.Objects.requireNonNull(state, "state");
    }
}
