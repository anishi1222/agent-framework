// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Truncates whole oldest groups after a message-count trigger.
 *
 * <p>The strategy never removes system/developer instructions, unresolved call/result or approval
 * groups, the latest group, or configured recent turns. If those groups exceed the target, the
 * original history is returned with an explicit required-content overflow audit.
 */
public final class TruncationCompactionStrategy implements CompactionStrategy {
    private final int maxMessages;

    private final int targetMessages;

    private final int preserveRecentTurns;

    /**
     * Creates a truncation strategy.
     *
     * @param maxMessages positive trigger count
     * @param targetMessages positive target count not greater than {@code maxMessages}
     * @param preserveRecentTurns non-negative number of newest turns to protect
     */
    public TruncationCompactionStrategy(int maxMessages, int targetMessages, int preserveRecentTurns) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than zero.");
        }
        if (targetMessages <= 0 || targetMessages > maxMessages) {
            throw new IllegalArgumentException("targetMessages must be positive and not exceed maxMessages.");
        }
        this.maxMessages = maxMessages;
        this.targetMessages = targetMessages;
        this.preserveRecentTurns = CompactionSupport.requireNonNegative(preserveRecentTurns, "preserveRecentTurns");
    }

    /** Returns the trigger count. */
    public int maxMessages() {
        return maxMessages;
    }

    /** Returns the target count. */
    public int targetMessages() {
        return targetMessages;
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
        if (request.messages().size() <= maxMessages) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, (long) targetMessages));
        }

        Set<String> protectedIds = CompactionSupport.protectedGroupIds(groups, preserveRecentTurns);
        BitSet retained = CompactionSupport.allIndexes(request.messages().size());
        for (CompactionMessageGroup group : groups) {
            if (CompactionSupport.retainedMessageCount(retained) <= targetMessages) {
                break;
            }
            if (!protectedIds.contains(group.id())) {
                CompactionSupport.remove(retained, group);
            }
        }
        CompactionLimitStatus status = CompactionSupport.retainedMessageCount(retained) <= targetMessages
                ? CompactionLimitStatus.WITHIN_LIMIT
                : CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT;
        if (status == CompactionLimitStatus.REQUIRED_CONTENT_EXCEEDS_LIMIT) {
            return CompletableFuture.completedFuture(CompactionSupport.unchanged(
                    getClass().getSimpleName(), request, groups, (long) targetMessages, status));
        }
        return CompletableFuture.completedFuture(CompactionSupport.result(
                getClass().getSimpleName(),
                request,
                groups,
                retained,
                null,
                -1,
                List.of(),
                (long) targetMessages,
                status));
    }
}
