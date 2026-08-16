// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Routes one source output concurrently to multiple targets. */
public final class FanOutEdgeGroup implements EdgeGroup {
    private final NodeId sourceId;

    private final List<NodeId> targetIds;

    /**
     * Creates a fan-out group.
     *
     * @param sourceId source node identifier
     * @param targetIds distinct target identifiers
     */
    public FanOutEdgeGroup(NodeId sourceId, List<NodeId> targetIds) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetIds, "targetIds");
        TreeSet<NodeId> sorted = new TreeSet<>();
        for (NodeId targetId : targetIds) {
            if (!sorted.add(Objects.requireNonNull(targetId, "targetId"))) {
                throw new WorkflowValidationException("Fan-out group contains duplicate target '" + targetId + "'.");
            }
        }
        if (sorted.isEmpty()) {
            throw new WorkflowValidationException("Fan-out group requires at least one target.");
        }
        this.targetIds = List.copyOf(sorted);
    }

    /**
     * Returns the one source node identifier.
     *
     * @return source node identifier
     */
    public NodeId sourceId() {
        return sourceId;
    }

    @Override
    public List<NodeId> sourceIds() {
        return List.of(sourceId);
    }

    @Override
    public List<NodeId> targetIds() {
        return targetIds;
    }
}
