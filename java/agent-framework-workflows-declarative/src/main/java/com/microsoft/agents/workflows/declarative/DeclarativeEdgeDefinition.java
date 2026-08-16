// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/** Defines one immutable declarative workflow edge or edge group. */
public sealed interface DeclarativeEdgeDefinition
        permits DirectEdgeDefinition, ConditionalEdgeDefinition, FanOutEdgeDefinition, FanInEdgeDefinition {
    /**
     * Returns the edge shape.
     *
     * @return edge kind
     */
    DeclarativeEdgeKind kind();
}
