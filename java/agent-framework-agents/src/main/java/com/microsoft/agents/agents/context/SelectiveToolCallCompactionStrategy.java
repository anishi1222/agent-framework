// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Removes older complete tool-call groups while retaining the configured newest groups.
 *
 * <p>Non-tool history and structurally protected unresolved call, result, approval, or preamble
 * groups are never removed. A zero keep count removes every other complete tool group.
 */
public final class SelectiveToolCallCompactionStrategy implements CompactionStrategy {
    private final int keepLastToolCallGroups;

    /**
     * Creates a selective tool-call strategy.
     *
     * @param keepLastToolCallGroups non-negative newest complete tool groups to retain
     */
    public SelectiveToolCallCompactionStrategy(int keepLastToolCallGroups) {
        this.keepLastToolCallGroups =
                CompactionSupport.requireNonNegative(keepLastToolCallGroups, "keepLastToolCallGroups");
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
        List<CompactionMessageGroup> groups = CompactionSupport.groups(request);
        List<CompactionMessageGroup> toolGroups = groups.stream()
                .filter(group -> group.kind() == CompactionGroupKind.TOOL)
                .toList();
        if (toolGroups.size() <= keepLastToolCallGroups) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, null));
        }

        Set<String> retainedToolIds = new HashSet<>();
        int firstRetained = Math.max(0, toolGroups.size() - keepLastToolCallGroups);
        for (int index = firstRetained; index < toolGroups.size(); index++) {
            retainedToolIds.add(toolGroups.get(index).id());
        }
        BitSet retained = CompactionSupport.allIndexes(request.messages().size());
        for (CompactionMessageGroup group : toolGroups) {
            if (!retainedToolIds.contains(group.id()) && !group.structurallyProtected()) {
                CompactionSupport.remove(retained, group);
            }
        }
        if (retained.cardinality() == request.messages().size()) {
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged(getClass().getSimpleName(), request, groups, null));
        }
        return CompletableFuture.completedFuture(CompactionSupport.result(
                getClass().getSimpleName(),
                request,
                groups,
                retained,
                null,
                -1,
                List.of(),
                null,
                CompactionLimitStatus.NOT_APPLICABLE));
    }
}
