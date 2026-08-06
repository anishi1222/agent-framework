// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.RunCancelledException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes agent middleware in registration order with single-use continuations.
 *
 * @param <T> structured response value type
 */
public final class AgentMiddlewarePipeline<T> {
    private final List<AgentMiddleware<T>> middleware;

    /**
     * Creates an immutable pipeline.
     *
     * @param middleware middleware in registration order
     */
    public AgentMiddlewarePipeline(Collection<? extends AgentMiddleware<T>> middleware) {
        AgentValidation.requireNonNull(middleware, "middleware");
        this.middleware = List.copyOf(middleware);
        if (this.middleware.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("middleware contains null");
        }
    }

    /**
     * Executes a finite pipeline.
     *
     * @param context immutable per-run context
     * @param terminal terminal handler
     * @return response stage
     */
    public CompletionStage<AgentResponse<T>> executeAsync(
            AgentMiddlewareContext<T> context, AgentMiddlewareNext<T> terminal) {
        return invokeAsync(0, context, AgentValidation.requireNonNull(terminal, "terminal"));
    }

    /**
     * Executes a streaming pipeline.
     *
     * @param context immutable per-run context
     * @param terminal terminal handler
     * @return updates and terminal response
     */
    public AgentStreamingResult<T> executeStreaming(
            AgentMiddlewareContext<T> context, AgentStreamingMiddlewareNext<T> terminal) {
        return invokeStreaming(0, context, AgentValidation.requireNonNull(terminal, "terminal"));
    }

    private CompletionStage<AgentResponse<T>> invokeAsync(
            int index, AgentMiddlewareContext<T> context, AgentMiddlewareNext<T> terminal) {
        if (context.runContext().cancellation().isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (index >= middleware.size()) {
            return requireStage(terminal.invokeAsync(context));
        }
        AtomicBoolean invoked = new AtomicBoolean();
        AgentMiddlewareNext<T> next = nextContext -> {
            if (!invoked.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new MiddlewareException("Agent middleware called next more than once."));
            }
            return invokeAsync(index + 1, AgentValidation.requireNonNull(nextContext, "context"), terminal);
        };
        try {
            return requireStage(middleware.get(index).invokeAsync(context, next));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private AgentStreamingResult<T> invokeStreaming(
            int index, AgentMiddlewareContext<T> context, AgentStreamingMiddlewareNext<T> terminal) {
        if (context.runContext().cancellation().isCancellationRequested()) {
            return failedStreaming(new RunCancelledException());
        }
        if (index >= middleware.size()) {
            return requireStreamingResult(terminal.invokeStreaming(context));
        }
        AtomicBoolean invoked = new AtomicBoolean();
        AgentStreamingMiddlewareNext<T> next = nextContext -> {
            if (!invoked.compareAndSet(false, true)) {
                return failedStreaming(new MiddlewareException("Agent middleware called next more than once."));
            }
            return invokeStreaming(index + 1, AgentValidation.requireNonNull(nextContext, "context"), terminal);
        };
        try {
            return requireStreamingResult(middleware.get(index).invokeStreaming(context, next));
        } catch (RuntimeException failure) {
            return failedStreaming(failure);
        }
    }

    private static <T> CompletionStage<T> requireStage(CompletionStage<T> stage) {
        return stage == null
                ? CompletableFuture.failedFuture(new MiddlewareException("Agent middleware returned a null stage."))
                : stage;
    }

    private static <T> AgentStreamingResult<T> requireStreamingResult(AgentStreamingResult<T> result) {
        return result == null
                ? failedStreaming(new MiddlewareException("Agent middleware returned a null streaming result."))
                : result;
    }

    private static <T> AgentStreamingResult<T> failedStreaming(Throwable failure) {
        return new AgentStreamingResult<>(
                MiddlewarePublishers.failed(failure), CompletableFuture.failedFuture(failure));
    }
}
