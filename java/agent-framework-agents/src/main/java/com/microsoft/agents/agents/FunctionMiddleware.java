// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/**
 * Intercepts actual function invocation from the agent layer.
 *
 * <p>Middleware may perform before/after work, short-circuit with a valid {@link StateValue}, or
 * translate failures. The supplied continuation may be invoked at most once.
 */
@FunctionalInterface
public interface FunctionMiddleware {
    /**
     * Intercepts one function invocation.
     *
     * @param context immutable function context
     * @param next single-use continuation
     * @return JSON-shaped result stage
     */
    CompletionStage<StateValue> invokeAsync(FunctionMiddlewareContext context, FunctionMiddlewareNext next);
}
