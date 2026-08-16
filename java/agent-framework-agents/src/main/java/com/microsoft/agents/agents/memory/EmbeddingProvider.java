// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Generates framework-owned embedding vectors without exposing a provider SDK. */
@FunctionalInterface
public interface EmbeddingProvider {
    /**
     * Generates one embedding.
     *
     * @param request scoped embedding request
     * @param cancellation caller-owned cancellation
     * @return finite embedding stage
     */
    CompletionStage<EmbeddingVector> generateAsync(EmbeddingRequest request, RunCancellation cancellation);
}
