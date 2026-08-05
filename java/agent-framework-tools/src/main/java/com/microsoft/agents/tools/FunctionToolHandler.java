// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.concurrent.CompletionStage;

/**
 * Implements one explicitly schema-bound function tool without reflective object binding.
 */
@FunctionalInterface
public interface FunctionToolHandler {
    /**
     * Invokes the function asynchronously.
     *
     * @param context invocation context
     * @param arguments validated JSON-shaped arguments
     * @return stage producing a JSON-shaped result
     */
    CompletionStage<StateValue> invokeAsync(ToolInvocationContext context, StateValue.ObjectValue arguments);
}
