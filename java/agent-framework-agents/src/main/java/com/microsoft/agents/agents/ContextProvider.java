// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Supplies immutable instructions, messages, metadata, or tools for one agent run.
 *
 * <p>Providers execute sequentially in registration order. Each provider observes contributions from
 * earlier providers. Implementations inspect {@link ContextProviderRequest#runContext()} for explicit
 * cancellation and must not mutate caller-owned lists or maps.
 */
public interface ContextProvider {
    /**
     * Returns the stable provider identifier used for ordering diagnostics.
     *
     * @return non-blank provider identifier
     */
    String id();

    /**
     * Provides context before model execution.
     *
     * @param request immutable accumulated request
     * @return stage producing an immutable contribution
     */
    CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request);

    /**
     * Observes terminal completion after a successful or failed run.
     *
     * <p>The default implementation performs no work. Provider failures propagate; the runtime never
     * silently falls back.
     *
     * @param completion immutable terminal notification
     * @return completion stage
     */
    default CompletionStage<Void> completedAsync(ContextProviderCompletion completion) {
        AgentValidation.requireNonNull(completion, "completion");
        return CompletableFuture.completedFuture(null);
    }
}
