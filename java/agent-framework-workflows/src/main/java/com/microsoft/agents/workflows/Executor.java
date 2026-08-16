// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateCodec;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Executes one typed workflow node.
 *
 * @param <I> accepted input payload type
 * @param <O> produced output payload type
 */
public interface Executor<I, O> {
    /**
     * Returns the runtime input type used for graph validation.
     *
     * @return input payload type
     */
    Class<I> inputType();

    /**
     * Returns the runtime output type used for graph validation.
     *
     * @return output payload type
     */
    Class<O> outputType();

    /**
     * Executes the node asynchronously.
     *
     * @param input typed input payload
     * @param context run-scoped workflow context
     * @return non-null completion stage producing a non-null output
     */
    CompletionStage<O> executeAsync(I input, WorkflowContext context);

    /**
     * Returns the optional input codec required to restore a pending invocation.
     *
     * @return input codec, or empty when pending inputs cannot be checkpointed
     */
    default Optional<StateCodec<I>> inputCodec() {
        return Optional.empty();
    }

    /**
     * Returns the optional output codec required to checkpoint a buffered fan-in value.
     *
     * @return output codec, or empty when buffered outputs cannot be checkpointed
     */
    default Optional<StateCodec<O>> outputCodec() {
        return Optional.empty();
    }
}
