// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import java.util.concurrent.CompletionStage;

/**
 * Continues one finite agent middleware pipeline.
 *
 * @param <T> structured response value type
 */
@FunctionalInterface
public interface AgentMiddlewareNext<T> {
    /**
     * Continues execution at most once.
     *
     * @param context immutable context
     * @return terminal response stage
     */
    CompletionStage<AgentResponse<T>> invokeAsync(AgentMiddlewareContext<T> context);
}
