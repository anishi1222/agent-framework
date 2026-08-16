// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Removes oldest whole groups until an estimated input-token budget is met.
 *
 * <p>If protected content alone exceeds the budget, the original history is returned unchanged and
 * the audit reports {@link CompactionLimitStatus#REQUIRED_CONTENT_EXCEEDS_LIMIT}; no message or tool
 * pair is split and no partial projection is persisted.
 */
public final class TokenBudgetCompactionStrategy implements CompactionStrategy {
    private final long tokenBudget;

    private final int preserveRecentTurns;

    /**
     * Creates a token-budget strategy.
     *
     * @param tokenBudget positive estimated-token budget
     * @param preserveRecentTurns non-negative number of newest turns to protect
     */
    public TokenBudgetCompactionStrategy(long tokenBudget, int preserveRecentTurns) {
        this.tokenBudget = CompactionSupport.requirePositive(tokenBudget, "tokenBudget");
        this.preserveRecentTurns = CompactionSupport.requireNonNegative(preserveRecentTurns, "preserveRecentTurns");
    }

    /** Returns the configured estimated-token budget. */
    public long tokenBudget() {
        return tokenBudget;
    }

    /** Returns the protected recent-turn count. */
    public int preserveRecentTurns() {
        return preserveRecentTurns;
    }

    @Override
    public CompletionStage<CompactionResult> compactAsync(CompactionRequest request) {
        CompletionStage<CompactionResult> cancelled = CompactionSupport.cancelledIfRequested(request);
        if (cancelled != null) {
            return cancelled;
        }
        List<CompactionMessageGroup> groups = CompactionSupport.groups(request);
        if (request.tokenEstimator().estimateTokens(request.messages()) <= tokenBudget) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, tokenBudget));
        }

        Set<String> protectedIds = CompactionSupport.protectedGroupIds(groups, preserveRecentTurns);
        BitSet retained = CompactionSupport.allIndexes(request.messages().size());
        for (CompactionMessageGroup group : groups) {
            if (CompactionSupport.retainedTokens(groups, retained) <= tokenBudget) {
                break;
            }
            if (!protectedIds.contains(group.id())) {
                CompactionSupport.remove(retained, group);
            }
        }
        CompactionLimitStatus status = CompactionSupport.retainedTokens(groups, retained) <= tokenBudget
                ? CompactionLimitStatus.WITHIN_LIMIT
                : CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT;
        if (status == CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, tokenBudget, status));
        }
        return CompletableFuture.completedFuture(CompactionSupport.result(
                getClass().getSimpleName(), request, groups, retained, null, -1, List.of(), tokenBudget, status));
    }
}
