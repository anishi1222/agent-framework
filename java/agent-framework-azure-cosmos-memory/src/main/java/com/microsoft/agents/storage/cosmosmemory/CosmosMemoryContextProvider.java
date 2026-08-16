// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.ContextProviderCompletion;
import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.MemoryContextProvider;
import com.microsoft.agents.agents.memory.EmbeddingProvider;
import com.microsoft.agents.agents.memory.MemoryContextOptions;
import com.microsoft.agents.agents.memory.MemoryScope;
import java.util.concurrent.CompletionStage;

/**
 * Adapts {@link CosmosMemoryStore} to bounded untrusted agent context injection.
 *
 * <p>The delegate adds user-role reference messages with citations and no instruction privilege.
 * Retrieved content is not persisted to history unless explicitly enabled in {@code contextOptions}.
 */
public final class CosmosMemoryContextProvider implements ContextProvider {
    private final MemoryContextProvider delegate;

    /**
     * Creates a Cosmos memory context provider.
     *
     * @param id stable context-provider identifier
     * @param store Cosmos memory store
     * @param scope explicit tenant and application scope
     * @param embeddingProvider optional provider used for hybrid retrieval
     * @param contextOptions bounded injection and persistence options
     */
    public CosmosMemoryContextProvider(
            String id,
            CosmosMemoryStore store,
            MemoryScope scope,
            EmbeddingProvider embeddingProvider,
            MemoryContextOptions contextOptions) {
        delegate = new MemoryContextProvider(id, store, scope, embeddingProvider, contextOptions);
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
        return delegate.provideAsync(request);
    }

    @Override
    public CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
        return delegate.completedAsync(completion);
    }
}
