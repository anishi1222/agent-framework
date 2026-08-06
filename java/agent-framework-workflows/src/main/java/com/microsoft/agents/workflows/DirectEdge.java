// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Objects;

/**
 * Routes every source output directly to one target.
 *
 * @param sourceId source node identifier
 * @param targetId target node identifier
 */
public record DirectEdge(NodeId sourceId, NodeId targetId) implements Edge {
    /** Creates a validated direct edge. */
    public DirectEdge {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetId, "targetId");
    }
}
