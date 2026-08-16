// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.concurrent.CompletionStage;

/**
 * Implements one cacheable functional workflow step.
 *
 * @param <I> step input type
 * @param <O> step output type
 */
@FunctionalInterface
public interface FunctionalStepFunction<I, O> {
    /**
     * Executes the step.
     *
     * @param input step input
     * @param context step-scoped workflow context
     * @return asynchronous step output
     */
    CompletionStage<O> execute(I input, FunctionalRunContext context);
}
