// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Defines immutable safety and diagnostic options for one function-calling loop.
 *
 * @param maxIterations positive provider-turn limit
 * @param maxFunctionCalls optional positive invocation limit
 * @param toolMode provider-neutral initial tool mode
 * @param includeDetailedErrors whether expected tool errors may include exception details
 * @param maxBufferedUpdates positive finite update-buffer limit for update-retaining runs; overflow
 *     fails and cancels the run
 */
public record FunctionInvocationOptions(
        int maxIterations,
        Integer maxFunctionCalls,
        ToolMode toolMode,
        boolean includeDetailedErrors,
        int maxBufferedUpdates) {
    /**
     * Default finite number of updates retained when downstream demand is slower than production.
     *
     * <p>This bound limits framework memory retention and does not imply end-to-end provider
     * throttling. Finite convenience calls that return only a result discard update emissions.
     */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    /** Creates validated immutable loop options. */
    public FunctionInvocationOptions {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than zero.");
        }
        if (maxFunctionCalls != null && maxFunctionCalls <= 0) {
            throw new IllegalArgumentException("maxFunctionCalls must be greater than zero when present.");
        }
        java.util.Objects.requireNonNull(toolMode, "toolMode");
        if (maxBufferedUpdates <= 0) {
            throw new IllegalArgumentException("maxBufferedUpdates must be greater than zero.");
        }
    }

    /**
     * Creates options using the default finite streaming-update buffer.
     *
     * @param maxIterations positive provider-turn limit
     * @param maxFunctionCalls optional positive invocation limit
     * @param toolMode provider-neutral initial tool mode
     * @param includeDetailedErrors whether expected tool errors may include exception details
     */
    public FunctionInvocationOptions(
            int maxIterations, Integer maxFunctionCalls, ToolMode toolMode, boolean includeDetailedErrors) {
        this(maxIterations, maxFunctionCalls, toolMode, includeDetailedErrors, DEFAULT_MAX_BUFFERED_UPDATES);
    }

    /**
     * Returns conservative default loop options.
     *
     * @return default options
     */
    public static FunctionInvocationOptions defaults() {
        return new FunctionInvocationOptions(40, null, ToolMode.AUTO, false, DEFAULT_MAX_BUFFERED_UPDATES);
    }
}
