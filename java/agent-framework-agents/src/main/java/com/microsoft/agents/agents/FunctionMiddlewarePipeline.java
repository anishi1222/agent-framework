// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes function middleware in registration order with single-use continuations. */
public final class FunctionMiddlewarePipeline {
    private final List<FunctionMiddleware> middleware;

    /**
     * Creates an immutable pipeline.
     *
     * @param middleware middleware in registration order
     */
    public FunctionMiddlewarePipeline(Collection<? extends FunctionMiddleware> middleware) {
        AgentValidation.requireNonNull(middleware, "middleware");
        this.middleware = List.copyOf(middleware);
        if (this.middleware.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("middleware contains null");
        }
    }

    /**
     * Executes the pipeline.
     *
     * @param context immutable function context
     * @param terminal actual function invocation
     * @return result stage
     */
    public CompletionStage<StateValue> executeAsync(
            FunctionMiddlewareContext context, FunctionMiddlewareNext terminal) {
        return invokeAsync(0, context, AgentValidation.requireNonNull(terminal, "terminal"));
    }

    private CompletionStage<StateValue> invokeAsync(
            int index, FunctionMiddlewareContext context, FunctionMiddlewareNext terminal) {
        if (context.invocation().invocation().cancellation().isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (index >= middleware.size()) {
            return requireStage(terminal.invokeAsync(context));
        }
        AtomicBoolean invoked = new AtomicBoolean();
        FunctionMiddlewareNext next = nextContext -> {
            if (!invoked.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new MiddlewareException("Function middleware called next more than once."));
            }
            return invokeAsync(index + 1, AgentValidation.requireNonNull(nextContext, "context"), terminal);
        };
        try {
            return requireStage(middleware.get(index).invokeAsync(context, next));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static CompletionStage<StateValue> requireStage(CompletionStage<StateValue> stage) {
        return stage == null
                ? CompletableFuture.failedFuture(new MiddlewareException("Function middleware returned a null stage."))
                : stage;
    }
}
