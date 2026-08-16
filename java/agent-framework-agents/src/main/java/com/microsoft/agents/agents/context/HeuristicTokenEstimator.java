// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;

/**
 * Estimates one token per four Unicode code points, rounded up, over a stable message encoding.
 *
 * <p>The estimate is intentionally conservative and deterministic rather than model-specific.
 */
public final class HeuristicTokenEstimator implements TokenEstimator {
    /** Shared stateless estimator. */
    public static final HeuristicTokenEstimator INSTANCE = new HeuristicTokenEstimator();

    private HeuristicTokenEstimator() {}

    @Override
    public long estimateTokens(Message message) {
        String stableText = CompactionText.message(message);
        long codePoints = stableText.codePointCount(0, stableText.length());
        return Math.max(1, (codePoints + 3) / 4);
    }
}
