// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import java.util.List;

/**
 * Describes one immutable compaction attempt.
 *
 * @param messages ordered source history
 * @param tokenEstimator provider-specific or heuristic estimator
 * @param cancellation caller-owned cancellation signal
 */
public record CompactionRequest(List<Message> messages, TokenEstimator tokenEstimator, RunCancellation cancellation) {
    /** Creates and defensively copies a request. */
    public CompactionRequest {
        messages = List.copyOf(messages);
        if (tokenEstimator == null) {
            throw new NullPointerException("tokenEstimator");
        }
        if (cancellation == null) {
            throw new NullPointerException("cancellation");
        }
    }
}
