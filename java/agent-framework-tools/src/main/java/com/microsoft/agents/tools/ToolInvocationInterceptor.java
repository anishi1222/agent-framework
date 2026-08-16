// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/**
 * Intercepts actual function-tool execution without depending on agent-layer types.
 *
 * <p>An interceptor may perform before/after work, return a valid result without calling the chain,
 * or translate failures. The supplied chain may be called at most once.
 */
@FunctionalInterface
public interface ToolInvocationInterceptor {
    /**
     * Intercepts one invocation.
     *
     * @param context immutable invocation context
     * @param chain single-use continuation
     * @return non-null stage producing a JSON-shaped result
     */
    CompletionStage<StateValue> interceptAsync(
            ToolInvocationInterceptContext context, ToolInvocationInterceptorChain chain);
}
