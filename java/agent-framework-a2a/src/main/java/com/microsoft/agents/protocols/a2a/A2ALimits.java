// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/**
 * Defines finite client, parser, stream, and concurrency bounds.
 *
 * @param maxRequestBytes maximum encoded request bytes
 * @param maxResponseBytes maximum finite response bytes
 * @param maxNestingDepth maximum JSON nesting depth
 * @param maxStringLength maximum JSON name or string length
 * @param maxCollectionEntries maximum entries per JSON object or array
 * @param maxEventBytes maximum encoded SSE event bytes
 * @param maxConcurrentRequests maximum in-flight operations
 * @param maxBufferedEvents maximum events retained for a slow subscriber
 */
public record A2ALimits(
        int maxRequestBytes,
        int maxResponseBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxCollectionEntries,
        int maxEventBytes,
        int maxConcurrentRequests,
        int maxBufferedEvents) {
    /** Creates validated finite limits. */
    public A2ALimits {
        A2AValidation.positive(maxRequestBytes, "maxRequestBytes");
        A2AValidation.positive(maxResponseBytes, "maxResponseBytes");
        A2AValidation.positive(maxNestingDepth, "maxNestingDepth");
        A2AValidation.positive(maxStringLength, "maxStringLength");
        A2AValidation.positive(maxCollectionEntries, "maxCollectionEntries");
        A2AValidation.positive(maxEventBytes, "maxEventBytes");
        A2AValidation.positive(maxConcurrentRequests, "maxConcurrentRequests");
        A2AValidation.positive(maxBufferedEvents, "maxBufferedEvents");
        if (maxEventBytes > maxResponseBytes) {
            throw new com.microsoft.agents.core.ValidationException("maxEventBytes must not exceed maxResponseBytes.");
        }
    }

    /**
     * Returns conservative defaults suitable for public internet peers.
     *
     * @return default limits
     */
    public static A2ALimits defaults() {
        return new A2ALimits(1024 * 1024, 4 * 1024 * 1024, 64, 256 * 1024, 10_000, 1024 * 1024, 64, 256);
    }
}
