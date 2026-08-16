// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Configures official SDK infinite-session compaction.
 *
 * @param enabled whether compaction is enabled
 * @param backgroundCompactionThreshold background compaction utilization threshold
 * @param bufferExhaustionThreshold blocking compaction utilization threshold
 */
public record GitHubCopilotInfiniteSessionConfig(
        boolean enabled, double backgroundCompactionThreshold, double bufferExhaustionThreshold) {
    /** Creates validated threshold configuration. */
    public GitHubCopilotInfiniteSessionConfig {
        range(backgroundCompactionThreshold, "backgroundCompactionThreshold");
        range(bufferExhaustionThreshold, "bufferExhaustionThreshold");
        if (backgroundCompactionThreshold >= bufferExhaustionThreshold) {
            throw new IllegalArgumentException(
                    "backgroundCompactionThreshold must be less than bufferExhaustionThreshold.");
        }
    }

    private static void range(double value, String name) {
        if (!Double.isFinite(value) || value <= 0 || value >= 1) {
            throw new IllegalArgumentException(name + " must be finite and between zero and one.");
        }
    }
}
