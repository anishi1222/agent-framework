// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Defines bounded request, response, SSE, JSON, de-duplication, and concurrency limits.
 *
 * @param maxRequestBytes maximum request bytes
 * @param maxResponseBytes maximum finite response bytes
 * @param maxEventBytes maximum SSE event bytes
 * @param maxLineBytes maximum SSE line bytes
 * @param maxNestingDepth maximum JSON depth
 * @param maxStringLength maximum JSON string length
 * @param maxCollectionEntries maximum entries in one JSON collection
 * @param maxBufferedEvents maximum undelivered events
 * @param maxRememberedActivityIds maximum de-duplication identities
 * @param maxConcurrentRequests maximum active requests
 */
public record CopilotStudioLimits(
        int maxRequestBytes,
        int maxResponseBytes,
        int maxEventBytes,
        int maxLineBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxCollectionEntries,
        int maxBufferedEvents,
        int maxRememberedActivityIds,
        int maxConcurrentRequests) {
    /** Creates validated positive limits. */
    public CopilotStudioLimits {
        positive(maxRequestBytes, "maxRequestBytes");
        positive(maxResponseBytes, "maxResponseBytes");
        positive(maxEventBytes, "maxEventBytes");
        positive(maxLineBytes, "maxLineBytes");
        positive(maxNestingDepth, "maxNestingDepth");
        positive(maxStringLength, "maxStringLength");
        positive(maxCollectionEntries, "maxCollectionEntries");
        positive(maxBufferedEvents, "maxBufferedEvents");
        positive(maxRememberedActivityIds, "maxRememberedActivityIds");
        positive(maxConcurrentRequests, "maxConcurrentRequests");
        if (maxEventBytes > maxResponseBytes || maxLineBytes > maxEventBytes) {
            throw new IllegalArgumentException("line and event limits must not exceed their enclosing limits.");
        }
    }

    /**
     * Returns conservative defaults.
     *
     * @return default limits
     */
    public static CopilotStudioLimits defaults() {
        return new CopilotStudioLimits(
                2 * 1024 * 1024, 8 * 1024 * 1024, 1024 * 1024, 256 * 1024, 64, 1024 * 1024, 100_000, 256, 4096, 32);
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
    }
}
