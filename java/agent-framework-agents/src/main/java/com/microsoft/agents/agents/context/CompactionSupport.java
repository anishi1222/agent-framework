// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        Map<Integer, List<Message>> insertions =
                summary == null ? Map.of() : Map.of(summaryInsertionIndex, List.of(summary));
        return resultWithInsertions(
                strategy, request, originalGroups, retained, insertions, summarizedIds, configuredLimit, limitStatus);
    }

    static CompactionResult resultWithInsertions(
            String strategy,
            CompactionRequest request,
            List<CompactionMessageGroup> originalGroups,
            BitSet retained,
            Map<Integer, List<Message>> insertions,
            List<String> summarizedIds,
            Long configuredLimit,
            CompactionLimitStatus limitStatus) {
        LinkedHashMap<Integer, List<Message>> safeInsertions = new LinkedHashMap<>();
        insertions.forEach((index, messages) -> {
            if (index < 0 || index > request.messages().size()) {
                throw new IllegalArgumentException("summary insertion index is outside the source history.");
            }
            safeInsertions.put(index, List.copyOf(messages));
        });
        ArrayList<Message> projected = new ArrayList<>();
        for (int index = 0; index <= request.messages().size(); index++) {
            projected.addAll(safeInsertions.getOrDefault(index, List.of()));
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
        List<String> summaryIds = safeInsertions.values().stream()
                .flatMap(List::stream)
                .map(Message::messageId)
                .filter(java.util.Objects::nonNull)
                .toList();
        String summaryId = summaryIds.size() == 1 ? summaryIds.getFirst() : null;
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
                summaryIds,
                configuredLimit,
                limitStatus);
        return new CompactionResult(projected, audit);
    }

    static CompactionResult projectedResult(
            String strategy,
            CompactionRequest request,
            List<Message> projected,
            Long configuredLimit,
            CompactionLimitStatus limitStatus) {
        List<Message> safeProjected = List.copyOf(projected);
        IdentityHashMap<Message, Integer> retainedByIdentity = new IdentityHashMap<>();
        IdentityHashMap<Message, Boolean> originalByIdentity = new IdentityHashMap<>();
        request.messages().forEach(message -> originalByIdentity.put(message, Boolean.TRUE));
        safeProjected.forEach(message -> retainedByIdentity.merge(message, 1, Integer::sum));
        ArrayList<String> removedIds = new ArrayList<>();
        for (int index = 0; index < request.messages().size(); index++) {
            Message message = request.messages().get(index);
            int count = retainedByIdentity.getOrDefault(message, 0);
            if (count == 0) {
                removedIds.add(CompactionText.identifier(message, index));
            } else if (count == 1) {
                retainedByIdentity.remove(message);
            } else {
                retainedByIdentity.put(message, count - 1);
            }
        }

        LinkedHashSet<String> summarizedIds = new LinkedHashSet<>();
        ArrayList<String> summaryIds = new ArrayList<>();
        for (Message message : safeProjected) {
            if (originalByIdentity.containsKey(message)) {
                continue;
            }
            StateValueSupport.addStrings(
                    message.metadata().get(SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY),
                    summarizedIds);
            if (message.metadata().containsKey(SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY)
                    && message.messageId() != null) {
                summaryIds.add(message.messageId());
            }
        }

        List<CompactionMessageGroup> originalGroups = groups(request);
        List<CompactionMessageGroup> projectedGroups =
                MessageGroupAnnotator.groupMessages(safeProjected, request.tokenEstimator());
        CompactionAudit audit = new CompactionAudit(
                strategy,
                !safeProjected.equals(request.messages()),
                request.messages().size(),
                safeProjected.size(),
                originalGroups.size(),
                projectedGroups.size(),
                request.tokenEstimator().estimateTokens(request.messages()),
                request.tokenEstimator().estimateTokens(safeProjected),
                removedIds,
                List.copyOf(summarizedIds),
                summaryIds.size() == 1 ? summaryIds.getFirst() : null,
                summaryIds,
                configuredLimit,
                limitStatus);
        return new CompactionResult(safeProjected, audit);
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

    private static final class StateValueSupport {
        private StateValueSupport() {}

        private static void addStrings(StateValue value, Set<String> target) {
            if (!(value instanceof StateValue.ArrayValue array)) {
                return;
            }
            for (StateValue item : array.values()) {
                if (item instanceof StateValue.StringValue string) {
                    target.add(string.value());
                }
            }
        }
    }
}
