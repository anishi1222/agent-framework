// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounds process-local suspended orchestration state.
 *
 * @param timeToLive maximum time abandoned state remains resumable
 * @param maxPendingContinuations maximum pending entries retained by one orchestration instance
 */
public record OrchestrationContinuationOptions(Duration timeToLive, int maxPendingContinuations) {
    private static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofMinutes(15);

    private static final int DEFAULT_MAX_PENDING_CONTINUATIONS = 128;

    /** Creates validated continuation storage options. */
    public OrchestrationContinuationOptions {
        timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        if (timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("timeToLive must be greater than zero.");
        }
        try {
            timeToLive.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("timeToLive is too large.", failure);
        }
        if (maxPendingContinuations <= 0) {
            throw new IllegalArgumentException("maxPendingContinuations must be greater than zero.");
        }
    }

    /**
     * Returns bounded framework defaults.
     *
     * @return fifteen-minute, 128-entry defaults
     */
    public static OrchestrationContinuationOptions defaults() {
        return new OrchestrationContinuationOptions(DEFAULT_TIME_TO_LIVE, DEFAULT_MAX_PENDING_CONTINUATIONS);
    }
}
