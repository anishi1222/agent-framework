// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Defines one asynchronous local evaluation check.
 */
public interface EvaluationCheck {
    /**
     * Returns the stable non-blank check name.
     *
     * @return check name
     */
    String name();

    /**
     * Evaluates one item asynchronously.
     *
     * @param item evaluation item
     * @param cancellation caller-owned cancellation signal
     * @return terminal check-result stage
     */
    CompletionStage<CheckResult> evaluateAsync(EvalItem item, RunCancellation cancellation);

    /**
     * Creates a named synchronous check.
     *
     * @param name non-blank check name
     * @param function synchronous check function
     * @return asynchronous check facade
     */
    static EvaluationCheck synchronous(String name, Function<EvalItem, CheckResult> function) {
        String checkedName = EvaluationValidation.requireNonBlank(name, "name");
        Function<EvalItem, CheckResult> checkedFunction = Objects.requireNonNull(function, "function");
        return asynchronous(checkedName, (item, cancellation) -> {
            if (cancellation.isCancellationRequested()) {
                return CompletableFuture.failedFuture(new RunCancelledException());
            }
            try {
                return CompletableFuture.completedFuture(checkedFunction.apply(item));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    /**
     * Creates a named asynchronous check.
     *
     * @param name non-blank check name
     * @param function asynchronous check function
     * @return evaluation check
     */
    static EvaluationCheck asynchronous(
            String name, BiFunction<EvalItem, RunCancellation, CompletionStage<CheckResult>> function) {
        String checkedName = EvaluationValidation.requireNonBlank(name, "name");
        BiFunction<EvalItem, RunCancellation, CompletionStage<CheckResult>> checkedFunction =
                Objects.requireNonNull(function, "function");
        return new EvaluationCheck() {
            @Override
            public String name() {
                return checkedName;
            }

            @Override
            public CompletionStage<CheckResult> evaluateAsync(EvalItem item, RunCancellation cancellation) {
                return checkedFunction.apply(
                        Objects.requireNonNull(item, "item"), Objects.requireNonNull(cancellation, "cancellation"));
            }
        };
    }
}
