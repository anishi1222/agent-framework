// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Estimates the model-token cost of framework-owned messages.
 *
 * <p>Implementations must be deterministic, thread-safe, and return a non-negative value. Provider
 * adapters may supply a model-specific implementation; {@link #heuristic()} is the safe,
 * provider-neutral default.
 */
@FunctionalInterface
public interface TokenEstimator {
    /**
     * Estimates tokens for one message.
     *
     * @param message immutable message
     * @return non-negative estimated token count
     */
    long estimateTokens(Message message);

    /**
     * Estimates tokens for ordered messages using saturating addition.
     *
     * @param messages ordered messages
     * @return non-negative estimated token count, or {@link Long#MAX_VALUE} on arithmetic overflow
     */
    default long estimateTokens(List<Message> messages) {
        if (messages == null) {
            throw new NullPointerException("messages");
        }
        long total = 0;
        for (Message message : messages) {
            long estimate = estimateTokens(message);
            if (estimate < 0) {
                throw new IllegalStateException("TokenEstimator returned a negative estimate.");
            }
            if (Long.MAX_VALUE - total < estimate) {
                return Long.MAX_VALUE;
            }
            total += estimate;
        }
        return total;
    }

    /**
     * Returns the deterministic provider-neutral estimator.
     *
     * @return shared heuristic estimator
     */
    static TokenEstimator heuristic() {
        return HeuristicTokenEstimator.INSTANCE;
    }
}
