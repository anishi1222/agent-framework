// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/** Continues one function middleware pipeline. */
@FunctionalInterface
public interface FunctionMiddlewareNext {
    /**
     * Continues execution at most once.
     *
     * @param context immutable function context
     * @return JSON-shaped result stage
     */
    CompletionStage<StateValue> invokeAsync(FunctionMiddlewareContext context);
}
