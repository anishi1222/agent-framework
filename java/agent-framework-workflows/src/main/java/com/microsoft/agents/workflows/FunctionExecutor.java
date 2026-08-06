// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.StateCodec;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * Adapts a typed Java function to a workflow {@link Executor}.
 *
 * @param <I> input payload type
 * @param <O> output payload type
 */
public final class FunctionExecutor<I, O> implements Executor<I, O> {
    private final Class<I> inputType;

    private final Class<O> outputType;

    private final BiFunction<? super I, ? super WorkflowContext, ? extends CompletionStage<? extends O>> function;

    private final StateCodec<I> inputCodec;

    private final StateCodec<O> outputCodec;

    private FunctionExecutor(
            Class<I> inputType,
            Class<O> outputType,
            BiFunction<? super I, ? super WorkflowContext, ? extends CompletionStage<? extends O>> function,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec) {
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.function = Objects.requireNonNull(function, "function");
        this.inputCodec = inputCodec;
        this.outputCodec = outputCodec;
    }

    /**
     * Creates an asynchronous function executor.
     *
     * @param inputType input payload type
     * @param outputType output payload type
     * @param function asynchronous node function
     * @param <I> input payload type
     * @param <O> output payload type
     * @return function executor
     */
    public static <I, O> FunctionExecutor<I, O> async(
            Class<I> inputType,
            Class<O> outputType,
            BiFunction<? super I, ? super WorkflowContext, ? extends CompletionStage<? extends O>> function) {
        return new FunctionExecutor<>(inputType, outputType, function, null, null);
    }

    /**
     * Creates an asynchronous function executor with checkpoint codecs.
     *
     * @param inputType input payload type
     * @param outputType output payload type
     * @param inputCodec codec for pending inputs
     * @param outputCodec codec for buffered outputs
     * @param function asynchronous node function
     * @param <I> input payload type
     * @param <O> output payload type
     * @return function executor
     */
    public static <I, O> FunctionExecutor<I, O> async(
            Class<I> inputType,
            Class<O> outputType,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec,
            BiFunction<? super I, ? super WorkflowContext, ? extends CompletionStage<? extends O>> function) {
        return new FunctionExecutor<>(
                inputType,
                outputType,
                function,
                Objects.requireNonNull(inputCodec, "inputCodec"),
                Objects.requireNonNull(outputCodec, "outputCodec"));
    }

    /**
     * Creates a synchronous function executor whose result is exposed through the asynchronous
     * executor contract.
     *
     * @param inputType input payload type
     * @param outputType output payload type
     * @param function synchronous node function
     * @param <I> input payload type
     * @param <O> output payload type
     * @return function executor
     */
    public static <I, O> FunctionExecutor<I, O> sync(
            Class<I> inputType,
            Class<O> outputType,
            BiFunction<? super I, ? super WorkflowContext, ? extends O> function) {
        Objects.requireNonNull(function, "function");
        return async(inputType, outputType, (input, context) -> {
            try {
                return CompletableFuture.completedFuture(function.apply(input, context));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    /**
     * Creates a synchronous function executor with checkpoint codecs.
     *
     * @param inputType input payload type
     * @param outputType output payload type
     * @param inputCodec codec for pending inputs
     * @param outputCodec codec for buffered outputs
     * @param function synchronous node function
     * @param <I> input payload type
     * @param <O> output payload type
     * @return function executor
     */
    public static <I, O> FunctionExecutor<I, O> sync(
            Class<I> inputType,
            Class<O> outputType,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec,
            BiFunction<? super I, ? super WorkflowContext, ? extends O> function) {
        Objects.requireNonNull(function, "function");
        return async(inputType, outputType, inputCodec, outputCodec, (input, context) -> {
            try {
                return CompletableFuture.completedFuture(function.apply(input, context));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    @Override
    public Class<I> inputType() {
        return inputType;
    }

    @Override
    public Class<O> outputType() {
        return outputType;
    }

    @Override
    public CompletionStage<O> executeAsync(I input, WorkflowContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        CompletionStage<? extends O> stage =
                Objects.requireNonNull(function.apply(inputType.cast(input), context), "function result");
        return stage.thenApply(value -> outputType.cast(Objects.requireNonNull(value, "executor output")));
    }

    @Override
    public Optional<StateCodec<I>> inputCodec() {
        return Optional.ofNullable(inputCodec);
    }

    @Override
    public Optional<StateCodec<O>> outputCodec() {
        return Optional.ofNullable(outputCodec);
    }
}
