// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Defines bounded process, JSON, event, and concurrency limits.
 *
 * @param maxProcessOutputLineBytes maximum optional-launcher output line bytes
 * @param maxDocumentBytes maximum JSON document bytes
 * @param maxNestingDepth maximum JSON nesting depth
 * @param maxStringLength maximum JSON string length
 * @param maxCollectionEntries maximum entries in one JSON collection
 * @param maxEventBytes maximum serialized event bytes
 * @param maxBufferedEvents maximum undelivered events per stream
 * @param maxStderrBytes maximum retained standard-error bytes
 * @param maxConcurrentRequests maximum concurrent client requests
 */
public record GitHubCopilotLimits(
        int maxProcessOutputLineBytes,
        int maxDocumentBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxCollectionEntries,
        int maxEventBytes,
        int maxBufferedEvents,
        int maxStderrBytes,
        int maxConcurrentRequests) {
    /** Creates validated positive limits. */
    public GitHubCopilotLimits {
        positive(maxProcessOutputLineBytes, "maxProcessOutputLineBytes");
        positive(maxDocumentBytes, "maxDocumentBytes");
        positive(maxNestingDepth, "maxNestingDepth");
        positive(maxStringLength, "maxStringLength");
        positive(maxCollectionEntries, "maxCollectionEntries");
        positive(maxEventBytes, "maxEventBytes");
        positive(maxBufferedEvents, "maxBufferedEvents");
        positive(maxStderrBytes, "maxStderrBytes");
        positive(maxConcurrentRequests, "maxConcurrentRequests");
        if (maxEventBytes > maxDocumentBytes || maxProcessOutputLineBytes > maxDocumentBytes) {
            throw new IllegalArgumentException("line and event limits must not exceed maxDocumentBytes.");
        }
    }

    /**
     * Returns conservative defaults.
     *
     * @return default limits
     */
    public static GitHubCopilotLimits defaults() {
        return new GitHubCopilotLimits(
                2 * 1024 * 1024, 8 * 1024 * 1024, 64, 1024 * 1024, 100_000, 2 * 1024 * 1024, 256, 256 * 1024, 32);
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
    }
}
