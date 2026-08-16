// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.concurrent.CompletionStage;

/**
 * Implements one functional workflow body using native Java control flow.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
@FunctionalInterface
public interface FunctionalWorkflowFunction<I, O> {
    /**
     * Executes the workflow body.
     *
     * @param input workflow input
     * @param context workflow run context
     * @return asynchronous workflow output
     */
    CompletionStage<O> execute(I input, FunctionalRunContext context);
}
