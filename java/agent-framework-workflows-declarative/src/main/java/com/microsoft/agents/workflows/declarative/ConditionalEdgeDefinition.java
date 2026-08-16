// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/**
 * Defines one caller-registered conditional workflow route.
 *
 * @param source source node identifier
 * @param target target node identifier
 * @param condition condition registry reference
 */
public record ConditionalEdgeDefinition(String source, String target, String condition)
        implements DeclarativeEdgeDefinition {
    /** Creates a validated conditional edge definition. */
    public ConditionalEdgeDefinition {
        source = WorkflowDefinitionValidation.requireNonBlank(source, "edge.source");
        target = WorkflowDefinitionValidation.requireNonBlank(target, "edge.target");
        condition = WorkflowDefinitionValidation.requireNonBlank(condition, "edge.condition");
    }

    @Override
    public DeclarativeEdgeKind kind() {
        return DeclarativeEdgeKind.CONDITIONAL;
    }
}
