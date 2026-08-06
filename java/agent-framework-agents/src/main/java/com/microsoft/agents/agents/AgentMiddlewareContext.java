// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/**
 * Carries immutable identity and per-run metadata for agent middleware.
 *
 * @param <T> structured response value type
 * @param agent agent being invoked
 * @param runContext explicit immutable run context
 * @param metadata isolated metadata runtime for this pipeline
 */
public record AgentMiddlewareContext<T>(Agent<T> agent, AgentRunContext runContext, MiddlewareMetadata metadata) {
    /** Creates a validated immutable middleware context. */
    public AgentMiddlewareContext {
        agent = AgentValidation.requireNonNull(agent, "agent");
        runContext = AgentValidation.requireNonNull(runContext, "runContext");
        metadata = AgentValidation.requireNonNull(metadata, "metadata");
    }
}
