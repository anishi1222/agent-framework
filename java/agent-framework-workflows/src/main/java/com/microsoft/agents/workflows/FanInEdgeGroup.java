// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Releases one {@link FanInInput} after every required source has arrived for the current epoch. */
public final class FanInEdgeGroup implements EdgeGroup {
    private final List<NodeId> sourceIds;

    private final NodeId targetId;

    /**
     * Creates a fan-in group.
     *
     * @param sourceIds distinct required source identifiers
     * @param targetId target node identifier
     */
    public FanInEdgeGroup(List<NodeId> sourceIds, NodeId targetId) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        TreeSet<NodeId> sorted = new TreeSet<>();
        for (NodeId sourceId : sourceIds) {
            if (!sorted.add(Objects.requireNonNull(sourceId, "sourceId"))) {
                throw new WorkflowValidationException("Fan-in group contains duplicate source '" + sourceId + "'.");
            }
        }
        if (sorted.size() < 2) {
            throw new WorkflowValidationException("Fan-in group requires at least two sources.");
        }
        this.sourceIds = List.copyOf(sorted);
        this.targetId = Objects.requireNonNull(targetId, "targetId");
    }

    @Override
    public List<NodeId> sourceIds() {
        return sourceIds;
    }

    /**
     * Returns the one target node identifier.
     *
     * @return target node identifier
     */
    public NodeId targetId() {
        return targetId;
    }

    @Override
    public List<NodeId> targetIds() {
        return List.of(targetId);
    }
}
