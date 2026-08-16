// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.Experimental;
import com.microsoft.agents.core.StateCodec;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Defines one typed, cacheable functional workflow step.
 *
 * @param <I> step input type
 * @param <O> step output type
 */
@Experimental("FUNCTIONAL_WORKFLOWS")
public final class FunctionalStep<I, O> {
    private final String name;

    private final Class<I> inputType;

    private final Class<O> outputType;

    private final StateCodec<I> inputCodec;

    private final StateCodec<O> outputCodec;

    private final FunctionalStepFunction<I, O> function;

    private FunctionalStep(
            String name,
            Class<I> inputType,
            Class<O> outputType,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec,
            FunctionalStepFunction<I, O> function) {
        this.name = WorkflowValidation.requireNonBlank(name, "step name");
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.inputCodec = Objects.requireNonNull(inputCodec, "inputCodec");
        this.outputCodec = Objects.requireNonNull(outputCodec, "outputCodec");
        WorkflowValidation.requireCodec(inputCodec);
        WorkflowValidation.requireCodec(outputCodec);
        this.function = Objects.requireNonNull(function, "function");
    }

    /**
     * Creates an asynchronous functional step.
     *
     * @param name stable step name
     * @param inputType input type
     * @param outputType output type
     * @param inputCodec input codec
     * @param outputCodec output codec
     * @param function step function
     * @param <I> input type
     * @param <O> output type
     * @return functional step
     */
    public static <I, O> FunctionalStep<I, O> async(
            String name,
            Class<I> inputType,
            Class<O> outputType,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec,
            FunctionalStepFunction<I, O> function) {
        return new FunctionalStep<>(name, inputType, outputType, inputCodec, outputCodec, function);
    }

    /**
     * Creates a synchronous functional step backed by the asynchronous execution core.
     *
     * @param name stable step name
     * @param inputType input type
     * @param outputType output type
     * @param inputCodec input codec
     * @param outputCodec output codec
     * @param function synchronous step function
     * @param <I> input type
     * @param <O> output type
     * @return functional step
     */
    public static <I, O> FunctionalStep<I, O> sync(
            String name,
            Class<I> inputType,
            Class<O> outputType,
            StateCodec<I> inputCodec,
            StateCodec<O> outputCodec,
            BiFunction<I, FunctionalRunContext, O> function) {
        Objects.requireNonNull(function, "function");
        return async(
                name,
                inputType,
                outputType,
                inputCodec,
                outputCodec,
                (input, context) -> CompletableFuture.completedFuture(function.apply(input, context)));
    }

    /**
     * Returns the stable step name used for events and replay keys.
     *
     * @return step name
     */
    public String name() {
        return name;
    }

    Class<I> inputType() {
        return inputType;
    }

    Class<O> outputType() {
        return outputType;
    }

    StateCodec<I> inputCodec() {
        return inputCodec;
    }

    StateCodec<O> outputCodec() {
        return outputCodec;
    }

    java.util.concurrent.CompletionStage<O> invoke(I input, FunctionalRunContext context) {
        return function.execute(input, context);
    }
}
