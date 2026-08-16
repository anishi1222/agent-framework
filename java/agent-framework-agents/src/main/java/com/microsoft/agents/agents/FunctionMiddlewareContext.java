// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.tools.ToolInvocationInterceptContext;

/**
 * Carries immutable data for one function middleware invocation.
 *
 * @param session optional active agent session
 * @param invocation provider-neutral tool interception context
 * @param metadata isolated metadata runtime
 */
public record FunctionMiddlewareContext(
        AgentSession session, ToolInvocationInterceptContext invocation, MiddlewareMetadata metadata) {
    /** Creates a validated immutable function context. */
    public FunctionMiddlewareContext {
        invocation = AgentValidation.requireNonNull(invocation, "invocation");
        metadata = AgentValidation.requireNonNull(metadata, "metadata");
    }
}
