// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import java.time.Duration;

/**
 * Defines bounded process-local Foundry hosting state.
 *
 * @param maximumSessions maximum session references
 * @param sessionTimeToLive session inactivity TTL
 * @param maximumContinuations maximum one-time requires-action handles
 * @param continuationTimeToLive continuation TTL
 * @param maximumSubmittedMessageIds maximum stable message identifiers retained per session
 * @param maxStoreRetries maximum optimistic-concurrency attempts
 */
public record FoundryHostingOptions(
        int maximumSessions,
        Duration sessionTimeToLive,
        int maximumContinuations,
        Duration continuationTimeToLive,
        int maximumSubmittedMessageIds,
        int maxStoreRetries) {
    /**
     * Creates options with production-safe message-id and optimistic-concurrency bounds.
     *
     * @param maximumSessions maximum session references
     * @param sessionTimeToLive session inactivity TTL
     * @param maximumContinuations maximum one-time requires-action handles
     * @param continuationTimeToLive continuation TTL
     */
    public FoundryHostingOptions(
            int maximumSessions,
            Duration sessionTimeToLive,
            int maximumContinuations,
            Duration continuationTimeToLive) {
        this(maximumSessions, sessionTimeToLive, maximumContinuations, continuationTimeToLive, 10_000, 3);
    }

    /** Creates and validates hosting options. */
    public FoundryHostingOptions {
        if (maximumSessions <= 0
                || maximumContinuations <= 0
                || maximumSubmittedMessageIds <= 0
                || maxStoreRetries <= 0) {
            throw new IllegalArgumentException("Hosting capacities must be positive.");
        }
        if (sessionTimeToLive == null
                || sessionTimeToLive.isZero()
                || sessionTimeToLive.isNegative()
                || continuationTimeToLive == null
                || continuationTimeToLive.isZero()
                || continuationTimeToLive.isNegative()) {
            throw new IllegalArgumentException("Hosting TTL values must be positive.");
        }
    }

    /** Returns production-safe bounded defaults. */
    public static FoundryHostingOptions defaults() {
        return new FoundryHostingOptions(10_000, Duration.ofHours(4), 1_000, Duration.ofMinutes(15), 10_000, 3);
    }
}
