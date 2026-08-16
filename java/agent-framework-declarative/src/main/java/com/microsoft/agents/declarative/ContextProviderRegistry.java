// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.agents.ContextProvider;
import java.util.Map;
import java.util.Optional;

/** Resolves declarative provider references to caller-owned context providers. */
@FunctionalInterface
public interface ContextProviderRegistry {
    /**
     * Finds a context provider by its logical identifier.
     *
     * @param id non-blank provider identifier
     * @return matching caller-owned provider, if registered
     */
    Optional<ContextProvider> find(String id);

    /**
     * Creates an immutable registry from a map.
     *
     * @param providers logical identifiers to caller-owned context providers
     * @return immutable registry
     */
    static ContextProviderRegistry of(Map<String, ? extends ContextProvider> providers) {
        Map<String, ContextProvider> copy = RegistrySupport.copy(providers, "contextProviders");
        return id -> Optional.ofNullable(copy.get(RegistrySupport.key(id, "context provider id")));
    }

    /**
     * Returns an empty registry.
     *
     * @return registry containing no context providers
     */
    static ContextProviderRegistry empty() {
        return of(Map.of());
    }
}
