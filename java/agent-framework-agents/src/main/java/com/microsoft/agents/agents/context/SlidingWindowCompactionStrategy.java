// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Retains the most recent logical user turns while preserving instructions and atomic structures.
 *
 * <p>If required structures make the configured window impossible, the original history is returned
 * with an explicit required-content overflow audit.
 */
public final class SlidingWindowCompactionStrategy implements CompactionStrategy {
    private final int keepLastTurns;

    /**
     * Creates a sliding-window strategy.
     *
     * @param keepLastTurns positive number of most-recent user turns to retain
     */
    public SlidingWindowCompactionStrategy(int keepLastTurns) {
        if (keepLastTurns <= 0) {
            throw new IllegalArgumentException("keepLastTurns must be greater than zero.");
        }
        this.keepLastTurns = keepLastTurns;
    }

    /**
     * Returns the configured turn window.
     *
     * @return positive retained-turn count
     */
    public int keepLastTurns() {
        return keepLastTurns;
    }

    @Override
    public CompletionStage<CompactionResult> compactAsync(CompactionRequest request) {
        CompletionStage<CompactionResult> cancelled = CompactionSupport.cancelledIfRequested(request);
        if (cancelled != null) {
            return cancelled;
        }
        List<CompactionMessageGroup> groups = CompactionSupport.groups(request);
        int maxTurn = groups.stream()
                .mapToInt(CompactionMessageGroup::turnIndex)
                .max()
                .orElse(0);
        if (maxTurn <= keepLastTurns) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, (long) keepLastTurns));
        }

        int firstRetainedTurn = maxTurn - keepLastTurns + 1;
        Set<String> protectedIds = CompactionSupport.protectedGroupIds(groups, keepLastTurns);
        BitSet retained = CompactionSupport.allIndexes(request.messages().size());
        for (CompactionMessageGroup group : groups) {
            if (group.turnIndex() >= 1 && group.turnIndex() < firstRetainedTurn && !protectedIds.contains(group.id())) {
                CompactionSupport.remove(retained, group);
            }
        }
        HashSet<Integer> remainingTurns = new HashSet<>();
        for (CompactionMessageGroup group : groups) {
            if (group.turnIndex() > 0 && group.messageIndexes().stream().anyMatch(retained::get)) {
                remainingTurns.add(group.turnIndex());
            }
        }
        CompactionLimitStatus status = remainingTurns.size() <= keepLastTurns
                ? CompactionLimitStatus.WITHIN_LIMIT
                : CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT;
        if (status == CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT) {
            return CompletableFuture.completedFuture(CompactionSupport.unchanged(
                    getClass().getSimpleName(), request, groups, (long) keepLastTurns, status));
        }
        return CompletableFuture.completedFuture(CompactionSupport.result(
                getClass().getSimpleName(),
                request,
                groups,
                retained,
                null,
                -1,
                List.of(),
                (long) keepLastTurns,
                status));
    }
}
