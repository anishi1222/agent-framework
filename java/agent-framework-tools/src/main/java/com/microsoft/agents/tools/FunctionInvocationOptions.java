// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Defines immutable safety and diagnostic options for one function-calling loop.
 *
 * @param maxIterations positive provider-turn limit
 * @param maxFunctionCalls optional positive invocation limit
 * @param toolMode provider-neutral initial tool mode
 * @param includeDetailedErrors whether expected tool errors may include exception details
 */
public record FunctionInvocationOptions(
        int maxIterations, Integer maxFunctionCalls, ToolMode toolMode, boolean includeDetailedErrors) {
    /** Creates validated immutable loop options. */
    public FunctionInvocationOptions {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than zero.");
        }
        if (maxFunctionCalls != null && maxFunctionCalls <= 0) {
            throw new IllegalArgumentException("maxFunctionCalls must be greater than zero when present.");
        }
        java.util.Objects.requireNonNull(toolMode, "toolMode");
    }

    /**
     * Returns conservative default loop options.
     *
     * @return default options
     */
    public static FunctionInvocationOptions defaults() {
        return new FunctionInvocationOptions(40, null, ToolMode.AUTO, false);
    }
}
