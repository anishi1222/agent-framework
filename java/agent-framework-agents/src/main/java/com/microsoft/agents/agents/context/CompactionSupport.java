// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancelledException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class CompactionSupport {
    private CompactionSupport() {}

    static CompletionStage<CompactionResult> cancelledIfRequested(CompactionRequest request) {
        if (request.cancellation().isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        return null;
    }

    static List<CompactionMessageGroup> groups(CompactionRequest request) {
        return MessageGroupAnnotator.groupMessages(request.messages(), request.tokenEstimator());
    }

    static Set<String> protectedGroupIds(List<CompactionMessageGroup> groups, int recentTurns) {
        if (recentTurns < 0) {
            throw new IllegalArgumentException("recentTurns must not be negative.");
        }
        int maxTurn = groups.stream()
                .mapToInt(CompactionMessageGroup::turnIndex)
                .max()
                .orElse(-1);
        int firstProtectedTurn = recentTurns == 0 ? Integer.MAX_VALUE : Math.max(1, maxTurn - recentTurns + 1);
        LinkedHashSet<String> protectedIds = new LinkedHashSet<>();
        for (CompactionMessageGroup group : groups) {
            if (group.kind() == CompactionGroupKind.INSTRUCTION
                    || group.structurallyProtected()
                    || group.turnIndex() >= firstProtectedTurn) {
                protectedIds.add(group.id());
            }
        }
        groups.stream()
                .filter(group -> group.kind() != CompactionGroupKind.INSTRUCTION)
                .reduce((left, right) -> right)
                .ifPresent(group -> protectedIds.add(group.id()));
        return Set.copyOf(protectedIds);
    }

    static BitSet allIndexes(int size) {
        BitSet retained = new BitSet(size);
        retained.set(0, size);
        return retained;
    }

    static void remove(BitSet retained, CompactionMessageGroup group) {
        group.messageIndexes().forEach(retained::clear);
    }

    static int retainedMessageCount(BitSet retained) {
        return retained.cardinality();
    }

    static long retainedTokens(List<CompactionMessageGroup> groups, BitSet retained) {
        long total = 0;
        for (CompactionMessageGroup group : groups) {
            if (group.messageIndexes().stream().anyMatch(retained::get)) {
                total = saturatedAdd(total, group.estimatedTokens());
            }
        }
        return total;
    }

    static CompactionResult result(
            String strategy,
            CompactionRequest request,
            List<CompactionMessageGroup> originalGroups,
            BitSet retained,
            Message summary,
            int summaryInsertionIndex,
            List<String> summarizedIds,
            Long configuredLimit,
            CompactionLimitStatus limitStatus) {
        ArrayList<Message> projected = new ArrayList<>();
        for (int index = 0; index <= request.messages().size(); index++) {
            if (summary != null && index == summaryInsertionIndex) {
                projected.add(summary);
            }
            if (index < request.messages().size() && retained.get(index)) {
                projected.add(request.messages().get(index));
            }
        }

        ArrayList<String> removedIds = new ArrayList<>();
        for (int index = 0; index < request.messages().size(); index++) {
            if (!retained.get(index)) {
                removedIds.add(CompactionText.identifier(request.messages().get(index), index));
            }
        }
        List<CompactionMessageGroup> projectedGroups =
                MessageGroupAnnotator.groupMessages(projected, request.tokenEstimator());
        long originalTokens = request.tokenEstimator().estimateTokens(request.messages());
        long projectedTokens = request.tokenEstimator().estimateTokens(projected);
        String summaryId = summary == null ? null : summary.messageId();
        CompactionAudit audit = new CompactionAudit(
                strategy,
                !projected.equals(request.messages()),
                request.messages().size(),
                projected.size(),
                originalGroups.size(),
                projectedGroups.size(),
                originalTokens,
                projectedTokens,
                removedIds,
                summarizedIds,
                summaryId,
                configuredLimit,
                limitStatus);
        return new CompactionResult(projected, audit);
    }

    static CompactionResult unchanged(
            String strategy, CompactionRequest request, List<CompactionMessageGroup> groups, Long configuredLimit) {
        CompactionLimitStatus status =
                configuredLimit == null ? CompactionLimitStatus.NOT_APPLICABLE : CompactionLimitStatus.WITHIN_LIMIT;
        return unchanged(strategy, request, groups, configuredLimit, status);
    }

    static CompactionResult unchanged(
            String strategy,
            CompactionRequest request,
            List<CompactionMessageGroup> groups,
            Long configuredLimit,
            CompactionLimitStatus status) {
        return result(
                strategy,
                request,
                groups,
                allIndexes(request.messages().size()),
                null,
                -1,
                List.of(),
                configuredLimit,
                status);
    }

    static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
        return value;
    }

    static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
