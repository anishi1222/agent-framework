// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/**
 * Continues one streaming agent middleware pipeline.
 *
 * @param <T> structured response value type
 */
@FunctionalInterface
public interface AgentStreamingMiddlewareNext<T> {
    /**
     * Continues streaming execution at most once.
     *
     * @param context immutable context
     * @return updates and terminal response
     */
    AgentStreamingResult<T> invokeStreaming(AgentMiddlewareContext<T> context);
}
