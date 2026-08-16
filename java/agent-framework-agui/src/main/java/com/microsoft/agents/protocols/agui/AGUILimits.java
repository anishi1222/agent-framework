// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/**
 * Defines mandatory bounds for AG-UI JSON, SSE, patch, and publisher processing.
 *
 * @param maxRequestBytes maximum JSON request bytes
 * @param maxResponseBytes maximum encoded object bytes
 * @param maxNestingDepth maximum JSON nesting
 * @param maxStringLength maximum string or member-name characters
 * @param maxNumericTokenLength maximum numeric token characters
 * @param maxCollectionEntries maximum members or items in one collection
 * @param maxPatchOperations maximum operations in one JSON Patch
 * @param maxSseFrameBytes maximum bytes in one SSE event frame
 * @param maxEventsPerRun maximum events accepted in one run
 * @param maxBufferedEvents maximum queued publisher events
 */
public record AGUILimits(
        long maxRequestBytes,
        long maxResponseBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxNumericTokenLength,
        int maxCollectionEntries,
        int maxPatchOperations,
        int maxSseFrameBytes,
        int maxEventsPerRun,
        int maxBufferedEvents) {
    /** Creates validated positive limits. */
    public AGUILimits {
        positive(maxRequestBytes, "maxRequestBytes");
        positive(maxResponseBytes, "maxResponseBytes");
        positive(maxNestingDepth, "maxNestingDepth");
        positive(maxStringLength, "maxStringLength");
        positive(maxNumericTokenLength, "maxNumericTokenLength");
        positive(maxCollectionEntries, "maxCollectionEntries");
        positive(maxPatchOperations, "maxPatchOperations");
        positive(maxSseFrameBytes, "maxSseFrameBytes");
        positive(maxEventsPerRun, "maxEventsPerRun");
        positive(maxBufferedEvents, "maxBufferedEvents");
        if (maxRequestBytes > Integer.MAX_VALUE || maxResponseBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("AG-UI byte limits must fit a Java array.");
        }
    }

    /**
     * Returns secure bounded defaults shared by the codec and client.
     *
     * @return default limits
     */
    public static AGUILimits defaults() {
        return new AGUILimits(
                1024L * 1024L, 4L * 1024L * 1024L, 64, 256 * 1024, 256, 10_000, 1_000, 256 * 1024, 10_000, 64);
    }

    private static void positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
    }
}
