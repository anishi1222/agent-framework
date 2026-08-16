// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancelledException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Applies ordered compaction strategies and a deterministic fallback until a token budget is met.
 *
 * <p>Each strategy receives the immutable projection produced by its predecessor. If configured,
 * execution stops as soon as the budget is satisfied. The fallback removes oldest complete groups
 * while preserving instructions, unresolved structures, and the latest group.
 */
public final class TokenBudgetComposedStrategy implements CompactionStrategy {
    private final long tokenBudget;

    private final List<CompactionStrategy> strategies;

    private final boolean earlyStop;

    /**
     * Creates a composed token-budget strategy.
     *
     * @param tokenBudget positive estimated-token budget
     * @param strategies ordered non-null strategies applied before fallback
     * @param earlyStop whether to stop as soon as the budget is satisfied
     */
    public TokenBudgetComposedStrategy(
            long tokenBudget, List<? extends CompactionStrategy> strategies, boolean earlyStop) {
        this.tokenBudget = CompactionSupport.requirePositive(tokenBudget, "tokenBudget");
        this.strategies = List.copyOf(strategies);
        if (this.strategies.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("strategies contains null");
        }
        this.earlyStop = earlyStop;
    }

    /**
     * Creates an early-stopping composed strategy.
     *
     * @param tokenBudget positive estimated-token budget
     * @param strategies ordered strategies applied before fallback
     */
    public TokenBudgetComposedStrategy(long tokenBudget, List<? extends CompactionStrategy> strategies) {
        this(tokenBudget, strategies, true);
    }

    /** Returns the estimated-token budget. */
    public long tokenBudget() {
        return tokenBudget;
    }

    /** Returns the ordered pre-fallback strategies. */
    public List<CompactionStrategy> strategies() {
        return strategies;
    }

    /** Returns whether strategy execution stops once the budget is satisfied. */
    public boolean earlyStop() {
        return earlyStop;
    }

    @Override
    public CompletionStage<CompactionResult> compactAsync(CompactionRequest request) {
        CompletionStage<CompactionResult> cancelled = CompactionSupport.cancelledIfRequested(request);
        if (cancelled != null) {
            return cancelled;
        }
        if (request.tokenEstimator().estimateTokens(request.messages()) <= tokenBudget) {
            return CompletableFuture.completedFuture(CompactionSupport.unchanged(
                    getClass().getSimpleName(), request, CompactionSupport.groups(request), tokenBudget));
        }
        return apply(request, request.messages(), 0).thenApply(projected -> {
            CompactionLimitStatus status = request.tokenEstimator().estimateTokens(projected) <= tokenBudget
                    ? CompactionLimitStatus.WITHIN_LIMIT
                    : CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT;
            return CompactionSupport.projectedResult(
                    getClass().getSimpleName(), request, projected, tokenBudget, status);
        });
    }

    private CompletionStage<List<Message>> apply(CompactionRequest request, List<Message> current, int index) {
        if (request.cancellation().isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (earlyStop && request.tokenEstimator().estimateTokens(current) <= tokenBudget) {
            return CompletableFuture.completedFuture(current);
        }
        if (index < strategies.size()) {
            return Compactions.compactAsync(
                            strategies.get(index), current, request.tokenEstimator(), request.cancellation())
                    .thenCompose(result -> {
                        if (result == null) {
                            return CompletableFuture.failedFuture(
                                    new CompactionException("A composed strategy returned a null result."));
                        }
                        return apply(request, result.messages(), index + 1);
                    });
        }
        if (request.tokenEstimator().estimateTokens(current) <= tokenBudget) {
            return CompletableFuture.completedFuture(current);
        }
        return Compactions.compactAsync(
                        new TokenBudgetCompactionStrategy(tokenBudget, 0),
                        current,
                        request.tokenEstimator(),
                        request.cancellation())
                .thenApply(result -> {
                    if (result == null) {
                        throw new CompactionException("The token-budget fallback returned a null result.");
                    }
                    return result.messages();
                });
    }
}
