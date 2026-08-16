// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.core.RunCancellation;
import java.util.concurrent.CompletionStage;

/** Evaluates whether a completed harness iteration should be reinvoked. */
@FunctionalInterface
public interface LoopEvaluator {
    /**
     * Evaluates one completed iteration.
     *
     * @param context immutable loop context with mutable per-run attributes
     * @param cancellation caller-owned cancellation
     * @return non-null evaluation stage
     */
    CompletionStage<LoopEvaluation> evaluateAsync(LoopContext context, RunCancellation cancellation);
}
