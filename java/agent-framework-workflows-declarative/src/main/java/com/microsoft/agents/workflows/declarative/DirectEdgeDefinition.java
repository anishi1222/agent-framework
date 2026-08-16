// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/**
 * Defines one direct workflow route.
 *
 * @param source source node identifier
 * @param target target node identifier
 */
public record DirectEdgeDefinition(String source, String target) implements DeclarativeEdgeDefinition {
    /** Creates a validated direct edge definition. */
    public DirectEdgeDefinition {
        source = WorkflowDefinitionValidation.requireNonBlank(source, "edge.source");
        target = WorkflowDefinitionValidation.requireNonBlank(target, "edge.target");
    }

    @Override
    public DeclarativeEdgeKind kind() {
        return DeclarativeEdgeKind.DIRECT;
    }
}
