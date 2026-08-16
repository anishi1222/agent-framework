// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import java.util.concurrent.CompletionStage;

/**
 * Compacts immutable history into an immutable projected result.
 *
 * <p>Implementations must be immutable, deterministic for deterministic dependencies, thread-safe,
 * cancellation-aware, and must never mutate {@link CompactionRequest#messages()}.
 */
@FunctionalInterface
public interface CompactionStrategy {
    /**
     * Compacts one history snapshot.
     *
     * @param request immutable request
     * @return non-null stage producing immutable projected history and audit metadata
     */
    CompletionStage<CompactionResult> compactAsync(CompactionRequest request);
}
