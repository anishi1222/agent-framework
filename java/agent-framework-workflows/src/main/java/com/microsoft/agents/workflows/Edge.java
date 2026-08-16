// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Describes one direct or conditional route between workflow nodes. */
public sealed interface Edge permits ConditionalEdge, DirectEdge {
    /**
     * Returns the source node identifier.
     *
     * @return source node identifier
     */
    NodeId sourceId();

    /**
     * Returns the target node identifier.
     *
     * @return target node identifier
     */
    NodeId targetId();
}
