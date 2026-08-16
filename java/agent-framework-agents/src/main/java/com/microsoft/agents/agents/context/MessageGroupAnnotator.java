// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Computes deterministic atomic message groups without mutating source messages. */
public final class MessageGroupAnnotator {
    private MessageGroupAnnotator() {}

    /**
     * Groups messages while preserving call/result and approval boundaries.
     *
     * <p>System and developer messages are instruction groups. Function results are linked to the
     * only unmatched declaration with the same call ID. Sequential reuse therefore forms separate
     * atomic pairs, while concurrently ambiguous or unmatched calls and results remain protected.
     * Non-summary preamble groups before the first user turn are also protected. Informational-only
     * calls do not await results. Duplicate message-derived group IDs receive a positional
     * discriminator only when required for uniqueness.
     *
     * @param messages ordered source messages
     * @param estimator token estimator
     * @return immutable groups ordered by first source index
     */
    public static List<CompactionMessageGroup> groupMessages(List<Message> messages, TokenEstimator estimator) {
        List<Message> source = List.copyOf(messages);
        if (estimator == null) {
            throw new NullPointerException("estimator");
        }
        if (source.isEmpty()) {
            return List.of();
        }

        int[] parents = new int[source.size()];
        int[] turnIndexes = new int[source.size()];
        boolean[] unresolved = new boolean[source.size()];
        int currentTurn = 0;
        for (int index = 0; index < source.size(); index++) {
            parents[index] = index;
            Message message = source.get(index);
            if (isInstruction(message)) {
                turnIndexes[index] = -1;
            } else {
                if (Role.USER.equals(message.role())) {
                    currentTurn++;
                }
                turnIndexes[index] = currentTurn;
            }
            if (hasApprovalMetadata(message)) {
                unresolved[index] = true;
            }
        }

        LinkedHashMap<String, ArrayDeque<Integer>> pendingCalls = new LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            for (Content content : source.get(index).contents()) {
                if (content instanceof FunctionCallContent call) {
                    if (!call.informationalOnly()) {
                        pendingCalls
                                .computeIfAbsent(call.callId(), ignored -> new ArrayDeque<>())
                                .addLast(index);
                    }
                } else if (content instanceof FunctionResultContent result) {
                    ArrayDeque<Integer> candidates = pendingCalls.get(result.callId());
                    if (candidates != null && candidates.size() == 1) {
                        int declaration = candidates.removeFirst();
                        union(parents, declaration, index);
                    } else {
                        unresolved[index] = true;
                        if (candidates != null) {
                            candidates.forEach(candidate -> unresolved[candidate] = true);
                        }
                    }
                }
            }
        }
        pendingCalls.values().forEach(indices -> indices.forEach(index -> unresolved[index] = true));

        TreeMap<Integer, ArrayList<Integer>> indexesByRoot = new TreeMap<>();
        for (int index = 0; index < source.size(); index++) {
            int root = find(parents, index);
            indexesByRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(index);
        }

        ArrayList<List<Integer>> orderedIndexes = new ArrayList<>(indexesByRoot.values());
        orderedIndexes.sort(java.util.Comparator.comparingInt(indexes -> indexes.getFirst()));
        List<String> groupIds = uniqueGroupIds(source, orderedIndexes);
        ArrayList<CompactionMessageGroup> groups = new ArrayList<>(orderedIndexes.size());
        for (int groupIndex = 0; groupIndex < orderedIndexes.size(); groupIndex++) {
            List<Integer> indexes = orderedIndexes.get(groupIndex);
            ArrayList<Message> groupMessages = new ArrayList<>(indexes.size());
            long tokens = 0;
            int turnIndex = -1;
            boolean structural = false;
            boolean hasTool = false;
            boolean hasUser = false;
            boolean hasInstruction = false;
            for (int index : indexes) {
                Message message = source.get(index);
                groupMessages.add(message);
                long estimate = estimator.estimateTokens(message);
                if (estimate < 0) {
                    throw new IllegalStateException("TokenEstimator returned a negative estimate.");
                }
                tokens = saturatedAdd(tokens, estimate);
                turnIndex = Math.max(turnIndex, turnIndexes[index]);
                structural |= unresolved[index] || turnIndexes[index] == 0 && !isGeneratedSummary(message);
                hasUser |= Role.USER.equals(message.role());
                hasInstruction |= isInstruction(message);
                hasTool |= message.contents().stream()
                        .anyMatch(content ->
                                content instanceof FunctionCallContent || content instanceof FunctionResultContent);
            }
            CompactionGroupKind kind = hasInstruction
                    ? CompactionGroupKind.INSTRUCTION
                    : hasTool
                            ? CompactionGroupKind.TOOL
                            : hasUser ? CompactionGroupKind.USER : CompactionGroupKind.ASSISTANT;
            groups.add(new CompactionMessageGroup(
                    groupIds.get(groupIndex),
                    kind,
                    groupMessages,
                    indexes,
                    hasInstruction ? -1 : turnIndex,
                    structural,
                    tokens));
        }
        return List.copyOf(groups);
    }

    private static List<String> uniqueGroupIds(List<Message> source, List<List<Integer>> orderedIndexes) {
        ArrayList<String> baseIds = new ArrayList<>(orderedIndexes.size());
        HashMap<String, Integer> counts = new HashMap<>();
        for (List<Integer> indexes : orderedIndexes) {
            int firstIndex = indexes.getFirst();
            String baseId = "group:" + CompactionText.identifier(source.get(firstIndex), firstIndex);
            baseIds.add(baseId);
            counts.merge(baseId, 1, Integer::sum);
        }

        boolean[] positional = new boolean[baseIds.size()];
        for (int index = 0; index < baseIds.size(); index++) {
            positional[index] = counts.get(baseIds.get(index)) > 1;
        }
        while (true) {
            List<String> candidates = candidateGroupIds(baseIds, orderedIndexes, positional);
            Map<String, List<Integer>> indexesById = new LinkedHashMap<>();
            for (int index = 0; index < candidates.size(); index++) {
                indexesById
                        .computeIfAbsent(candidates.get(index), ignored -> new ArrayList<>())
                        .add(index);
            }
            boolean collision = false;
            for (List<Integer> indexes : indexesById.values()) {
                if (indexes.size() > 1) {
                    collision = true;
                    indexes.forEach(index -> positional[index] = true);
                }
            }
            if (!collision) {
                return List.copyOf(candidates);
            }
        }
    }

    private static List<String> candidateGroupIds(
            List<String> baseIds, List<List<Integer>> orderedIndexes, boolean[] positional) {
        ArrayList<String> candidates = new ArrayList<>(baseIds.size());
        for (int index = 0; index < baseIds.size(); index++) {
            String baseId = baseIds.get(index);
            if (positional[index]) {
                int firstIndex = orderedIndexes.get(index).getFirst();
                candidates.add("group:position:" + firstIndex + ":" + baseId.length() + ":" + baseId);
            } else {
                candidates.add(baseId);
            }
        }
        return candidates;
    }

    static boolean isInstruction(Message message) {
        String role = message.role().value();
        return "system".equals(role) || "developer".equals(role);
    }

    private static boolean hasApprovalMetadata(Message message) {
        return message.metadata().keySet().stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .anyMatch(key -> key.contains("approval"));
    }

    private static boolean isGeneratedSummary(Message message) {
        return message.metadata().containsKey(SummarizationCompactionStrategy.SUMMARY_OF_MESSAGE_IDS_METADATA_KEY)
                && message.metadata().containsKey(SummarizationCompactionStrategy.SUMMARY_OF_GROUP_IDS_METADATA_KEY);
    }

    private static int find(int[] parents, int index) {
        int current = index;
        while (parents[current] != current) {
            parents[current] = parents[parents[current]];
            current = parents[current];
        }
        return current;
    }

    private static void union(int[] parents, int left, int right) {
        int leftRoot = find(parents, left);
        int rightRoot = find(parents, right);
        if (leftRoot == rightRoot) {
            return;
        }
        int first = Math.min(leftRoot, rightRoot);
        int second = Math.max(leftRoot, rightRoot);
        parents[second] = first;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
