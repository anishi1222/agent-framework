// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Executes immutable compaction strategies with explicit estimator and cancellation choices. */
public final class Compactions {
    private Compactions() {}

    /**
     * Compacts messages using the deterministic heuristic estimator.
     *
     * @param strategy compaction strategy
     * @param messages ordered source history
     * @return result stage
     */
    public static CompletionStage<CompactionResult> compactAsync(CompactionStrategy strategy, List<Message> messages) {
        return compactAsync(strategy, messages, TokenEstimator.heuristic(), new DefaultRunCancellation());
    }

    /**
     * Compacts messages with explicit dependencies.
     *
     * @param strategy compaction strategy
     * @param messages ordered source history
     * @param estimator token estimator
     * @param cancellation cancellation signal
     * @return result stage
     */
    public static CompletionStage<CompactionResult> compactAsync(
            CompactionStrategy strategy,
            List<Message> messages,
            TokenEstimator estimator,
            RunCancellation cancellation) {
        if (strategy == null) {
            throw new NullPointerException("strategy");
        }
        CompletionStage<CompactionResult> stage =
                strategy.compactAsync(new CompactionRequest(messages, estimator, cancellation));
        return stage == null
                ? java.util.concurrent.CompletableFuture.failedFuture(
                        new CompactionException("CompactionStrategy.compactAsync returned null."))
                : stage;
    }
}
