// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.RunCancelledException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Derives staged tool-result and truncation budgets from a model context-window size.
 *
 * <p>Older tool groups are summarized after the tool-eviction threshold. If the projected history
 * still exceeds the truncation threshold, oldest complete groups are removed toward the lower
 * tool-eviction budget. Required instructions, unresolved structures, and the latest group remain
 * protected even when they alone exceed a threshold.
 */
public final class ContextWindowCompactionStrategy implements CompactionStrategy {
    /** Default input-budget fraction that starts tool-result eviction. */
    public static final double DEFAULT_TOOL_EVICTION_THRESHOLD = 0.5;

    /** Default input-budget fraction that starts general truncation. */
    public static final double DEFAULT_TRUNCATION_THRESHOLD = 0.8;

    private final long maxContextWindowTokens;

    private final long maxOutputTokens;

    private final long inputBudgetTokens;

    private final double toolEvictionThreshold;

    private final double truncationThreshold;

    private final int keepLastToolCallGroups;

    private final long toolEvictionTokens;

    private final long truncationTokens;

    /**
     * Creates a context-window strategy.
     *
     * @param maxContextWindowTokens positive model context-window size
     * @param maxOutputTokens non-negative output reservation smaller than the context window
     * @param toolEvictionThreshold fraction in {@code (0, 1]}
     * @param truncationThreshold fraction in {@code (0, 1]} not below tool eviction
     * @param keepLastToolCallGroups non-negative newest tool groups retained verbatim
     */
    public ContextWindowCompactionStrategy(
            long maxContextWindowTokens,
            long maxOutputTokens,
            double toolEvictionThreshold,
            double truncationThreshold,
            int keepLastToolCallGroups) {
        if (maxContextWindowTokens <= 0) {
            throw new IllegalArgumentException("maxContextWindowTokens must be greater than zero.");
        }
        if (maxOutputTokens < 0 || maxOutputTokens >= maxContextWindowTokens) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be non-negative and less than maxContextWindowTokens.");
        }
        requireThreshold(toolEvictionThreshold, "toolEvictionThreshold");
        requireThreshold(truncationThreshold, "truncationThreshold");
        if (truncationThreshold < toolEvictionThreshold) {
            throw new IllegalArgumentException("truncationThreshold must not be less than toolEvictionThreshold.");
        }
        this.maxContextWindowTokens = maxContextWindowTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.inputBudgetTokens = maxContextWindowTokens - maxOutputTokens;
        this.toolEvictionThreshold = toolEvictionThreshold;
        this.truncationThreshold = truncationThreshold;
        this.keepLastToolCallGroups =
                CompactionSupport.requireNonNegative(keepLastToolCallGroups, "keepLastToolCallGroups");
        this.toolEvictionTokens = thresholdTokens(inputBudgetTokens, toolEvictionThreshold);
        this.truncationTokens = thresholdTokens(inputBudgetTokens, truncationThreshold);
    }

    /**
     * Creates a strategy with the default thresholds and four retained tool groups.
     *
     * @param maxContextWindowTokens positive model context-window size
     * @param maxOutputTokens non-negative output reservation
     */
    public ContextWindowCompactionStrategy(long maxContextWindowTokens, long maxOutputTokens) {
        this(maxContextWindowTokens, maxOutputTokens, DEFAULT_TOOL_EVICTION_THRESHOLD, DEFAULT_TRUNCATION_THRESHOLD, 4);
    }

    /** Returns the model context-window size. */
    public long maxContextWindowTokens() {
        return maxContextWindowTokens;
    }

    /** Returns the output-token reservation. */
    public long maxOutputTokens() {
        return maxOutputTokens;
    }

    /** Returns the derived input budget. */
    public long inputBudgetTokens() {
        return inputBudgetTokens;
    }

    /** Returns the tool-eviction threshold fraction. */
    public double toolEvictionThreshold() {
        return toolEvictionThreshold;
    }

    /** Returns the truncation threshold fraction. */
    public double truncationThreshold() {
        return truncationThreshold;
    }

    /** Returns the number of newest tool groups retained verbatim. */
    public int keepLastToolCallGroups() {
        return keepLastToolCallGroups;
    }

    @Override
    public CompletionStage<CompactionResult> compactAsync(CompactionRequest request) {
        CompletionStage<CompactionResult> cancelled = CompactionSupport.cancelledIfRequested(request);
        if (cancelled != null) {
            return cancelled;
        }
        TokenBudgetComposedStrategy toolEviction = new TokenBudgetComposedStrategy(
                toolEvictionTokens, List.of(new ToolResultCompactionStrategy(keepLastToolCallGroups)));
        return Compactions.compactAsync(
                        toolEviction, request.messages(), request.tokenEstimator(), request.cancellation())
                .thenCompose(toolResult -> {
                    if (request.cancellation().isCancellationRequested()) {
                        return CompletableFuture.failedFuture(new RunCancelledException());
                    }
                    if (request.tokenEstimator().estimateTokens(toolResult.messages()) <= truncationTokens) {
                        return CompletableFuture.completedFuture(toolResult.messages());
                    }
                    return Compactions.compactAsync(
                                    new TokenBudgetCompactionStrategy(toolEvictionTokens, 0),
                                    toolResult.messages(),
                                    request.tokenEstimator(),
                                    request.cancellation())
                            .thenApply(CompactionResult::messages);
                })
                .thenApply(projected -> {
                    CompactionLimitStatus status =
                            request.tokenEstimator().estimateTokens(projected) <= inputBudgetTokens
                                    ? CompactionLimitStatus.WITHIN_LIMIT
                                    : CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT;
                    return CompactionSupport.projectedResult(
                            getClass().getSimpleName(), request, projected, inputBudgetTokens, status);
                });
    }

    private static void requireThreshold(double value, String name) {
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be finite and in (0, 1].");
        }
    }

    private static long thresholdTokens(long inputBudget, double threshold) {
        return Math.max(1, (long) Math.floor(inputBudget * threshold));
    }
}
