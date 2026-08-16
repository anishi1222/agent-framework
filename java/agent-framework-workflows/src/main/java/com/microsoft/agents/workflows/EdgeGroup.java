// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.List;

/** Describes a fan-out or fan-in routing group. */
public sealed interface EdgeGroup permits FanInEdgeGroup, FanOutEdgeGroup {
    /**
     * Returns all source node identifiers in stable order.
     *
     * @return source node identifiers
     */
    List<NodeId> sourceIds();

    /**
     * Returns all target node identifiers in stable order.
     *
     * @return target node identifiers
     */
    List<NodeId> targetIds();
}
