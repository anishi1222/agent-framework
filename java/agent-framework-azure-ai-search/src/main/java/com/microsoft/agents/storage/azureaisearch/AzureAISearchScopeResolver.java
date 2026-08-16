// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.memory.MemoryScope;
import java.util.Objects;

/**
 * Resolves trusted application-owned tenant and scope values for one search request.
 *
 * <p>The provider never derives these isolation values from prompts, model output, or run metadata.
 */
@FunctionalInterface
public interface AzureAISearchScopeResolver {
    /**
     * Resolves one explicit tenant and scope.
     *
     * @param request immutable provider request
     * @return non-null scope
     */
    MemoryScope resolve(ContextProviderRequest request);

    /**
     * Creates a fixed resolver.
     *
     * @param scope fixed tenant and scope
     * @return resolver returning that scope
     */
    static AzureAISearchScopeResolver fixed(MemoryScope scope) {
        MemoryScope checked = Objects.requireNonNull(scope, "scope");
        return ignored -> checked;
    }
}
