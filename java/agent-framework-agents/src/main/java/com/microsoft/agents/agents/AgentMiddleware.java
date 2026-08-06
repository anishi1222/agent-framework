// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import java.util.concurrent.CompletionStage;

/**
 * Intercepts finite and streaming agent execution.
 *
 * <p>Implementations may perform before/after work, short-circuit with a valid response or publisher,
 * or translate failures. Each supplied continuation may be invoked at most once.
 *
 * @param <T> structured response value type
 */
public interface AgentMiddleware<T> {
    /**
     * Intercepts a finite agent run.
     *
     * @param context immutable per-run context
     * @param next single-use continuation
     * @return terminal response stage
     */
    default CompletionStage<AgentResponse<T>> invokeAsync(
            AgentMiddlewareContext<T> context, AgentMiddlewareNext<T> next) {
        return next.invokeAsync(context);
    }

    /**
     * Intercepts a streaming agent run.
     *
     * @param context immutable per-run context
     * @param next single-use continuation
     * @return updates and terminal response
     */
    default AgentStreamingResult<T> invokeStreaming(
            AgentMiddlewareContext<T> context, AgentStreamingMiddlewareNext<T> next) {
        return next.invokeStreaming(context);
    }
}
