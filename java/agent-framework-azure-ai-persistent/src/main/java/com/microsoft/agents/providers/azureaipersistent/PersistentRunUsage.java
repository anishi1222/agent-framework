// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/**
 * Token usage for one terminal persistent run.
 *
 * @param promptTokens prompt tokens consumed
 * @param completionTokens completion tokens produced
 * @param totalTokens total tokens reported by the service
 */
public record PersistentRunUsage(long promptTokens, long completionTokens, long totalTokens) {
    /** Creates and validates usage. */
    public PersistentRunUsage {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative.");
        }
    }
}
