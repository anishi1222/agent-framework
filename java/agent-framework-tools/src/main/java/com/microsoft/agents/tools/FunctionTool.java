// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.SynchronousExecutionException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Defines a provider-neutral function tool with explicit schemas and one asynchronous execution path.
 */
public interface FunctionTool extends Tool {
    /**
     * Invokes this function using validated JSON-shaped arguments.
     *
     * @param context invocation context
     * @param arguments function arguments
     * @return stage producing the JSON-shaped result
     */
    CompletionStage<StateValue> invokeAsync(ToolInvocationContext context, StateValue.ObjectValue arguments);

    /**
     * Invokes this function synchronously through {@link #invokeAsync(ToolInvocationContext,
     * StateValue.ObjectValue)}.
     *
     * @param context invocation context
     * @param arguments function arguments
     * @return JSON-shaped result
     * @throws RunCancelledException when cancellation wins
     * @throws SynchronousExecutionException when interrupted or asynchronous execution fails
     */
    default StateValue invoke(ToolInvocationContext context, StateValue.ObjectValue arguments) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(arguments, "arguments");
        try {
            return invokeAsync(context, arguments).toCompletableFuture().get();
        } catch (InterruptedException exception) {
            context.cancellation().cancel();
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException("Function invocation was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception.getCause());
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            throw new SynchronousExecutionException("Function invocation failed.", cause);
        }
    }

    /**
     * Creates an explicit function tool adapter.
     *
     * @param metadata immutable function metadata
     * @param handler JSON-shaped asynchronous handler
     * @return function tool
     */
    static FunctionTool create(ToolMetadata metadata, FunctionToolHandler handler) {
        return FunctionTools.create(metadata, handler);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
