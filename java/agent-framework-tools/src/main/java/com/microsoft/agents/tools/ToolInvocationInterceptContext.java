// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/**
 * Carries immutable provider-neutral data for one intercepted function invocation.
 *
 * @param tool function tool
 * @param invocation invocation identity, cancellation, executor, and metadata
 * @param arguments validated JSON-shaped arguments
 */
public record ToolInvocationInterceptContext(
        FunctionTool tool, ToolInvocationContext invocation, StateValue.ObjectValue arguments) {
    /** Creates a validated immutable interception context. */
    public ToolInvocationInterceptContext {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(arguments, "arguments");
    }
}
