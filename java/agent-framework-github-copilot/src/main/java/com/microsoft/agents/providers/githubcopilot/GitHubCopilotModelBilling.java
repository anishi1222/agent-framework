// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Describes billing information reported by the official SDK.
 *
 * @param multiplier optional request multiplier
 * @param tokenPrices optional token-price details
 */
public record GitHubCopilotModelBilling(Double multiplier, TokenPrices tokenPrices) {
    /** Creates validated billing information. */
    public GitHubCopilotModelBilling {
        if (multiplier != null && (!Double.isFinite(multiplier) || multiplier < 0)) {
            throw new IllegalArgumentException("multiplier must be finite and non-negative.");
        }
    }

    /**
     * Describes token prices reported by the official SDK.
     *
     * @param inputPrice optional input-token price
     * @param outputPrice optional output-token price
     * @param cachePrice optional cache price
     * @param cacheReadPrice optional cache-read price
     * @param cacheWritePrice optional cache-write price
     * @param batchSize optional billing batch size
     * @param contextMax optional context threshold
     * @param maxPromptTokens optional maximum prompt tokens
     * @param longContext optional long-context prices
     */
    public record TokenPrices(
            Double inputPrice,
            Double outputPrice,
            Double cachePrice,
            Double cacheReadPrice,
            Double cacheWritePrice,
            Long batchSize,
            Long contextMax,
            Long maxPromptTokens,
            LongContextPrices longContext) {
        /** Creates validated token prices. */
        public TokenPrices {
            nonNegative(inputPrice, "inputPrice");
            nonNegative(outputPrice, "outputPrice");
            nonNegative(cachePrice, "cachePrice");
            nonNegative(cacheReadPrice, "cacheReadPrice");
            nonNegative(cacheWritePrice, "cacheWritePrice");
            nonNegative(batchSize, "batchSize");
            nonNegative(contextMax, "contextMax");
            nonNegative(maxPromptTokens, "maxPromptTokens");
        }
    }

    /**
     * Describes prices applied above a long-context threshold.
     *
     * @param inputPrice optional input-token price
     * @param outputPrice optional output-token price
     * @param cachePrice optional cache price
     * @param cacheReadPrice optional cache-read price
     * @param cacheWritePrice optional cache-write price
     * @param contextMax optional context threshold
     * @param maxPromptTokens optional maximum prompt tokens
     */
    public record LongContextPrices(
            Double inputPrice,
            Double outputPrice,
            Double cachePrice,
            Double cacheReadPrice,
            Double cacheWritePrice,
            Long contextMax,
            Long maxPromptTokens) {
        /** Creates validated long-context prices. */
        public LongContextPrices {
            nonNegative(inputPrice, "inputPrice");
            nonNegative(outputPrice, "outputPrice");
            nonNegative(cachePrice, "cachePrice");
            nonNegative(cacheReadPrice, "cacheReadPrice");
            nonNegative(cacheWritePrice, "cacheWritePrice");
            nonNegative(contextMax, "contextMax");
            nonNegative(maxPromptTokens, "maxPromptTokens");
        }
    }

    private static void nonNegative(Double value, String name) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
    }

    private static void nonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative.");
        }
    }
}
