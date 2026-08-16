// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/**
 * Binds one stable workflow node identifier to a caller-registered executor.
 *
 * @param id stable node identifier
 * @param executor executor registry reference
 */
public record DeclarativeNodeDefinition(String id, String executor) {
    /** Creates a validated immutable node definition. */
    public DeclarativeNodeDefinition {
        id = WorkflowDefinitionValidation.requireNonBlank(id, "node.id");
        executor = WorkflowDefinitionValidation.requireNonBlank(executor, "node.executor");
    }
}
