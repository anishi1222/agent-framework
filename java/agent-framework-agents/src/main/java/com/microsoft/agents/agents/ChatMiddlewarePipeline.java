// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancelledException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes chat middleware in registration order with single-use continuations. */
public final class ChatMiddlewarePipeline {
    private final List<ChatMiddleware> middleware;

    /**
     * Creates an immutable pipeline.
     *
     * @param middleware middleware in registration order
     */
    public ChatMiddlewarePipeline(Collection<? extends ChatMiddleware> middleware) {
        AgentValidation.requireNonNull(middleware, "middleware");
        this.middleware = List.copyOf(middleware);
        if (this.middleware.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("middleware contains null");
        }
    }

    /**
     * Executes a finite pipeline.
     *
     * @param context immutable chat context
     * @param terminal terminal client call
     * @return response stage
     */
    public CompletionStage<ChatResponse> executeAsync(ChatMiddlewareContext context, ChatMiddlewareNext terminal) {
        return invokeAsync(0, context, AgentValidation.requireNonNull(terminal, "terminal"));
    }

    /**
     * Executes a streaming pipeline.
     *
     * @param context immutable chat context
     * @param terminal terminal client call
     * @return update publisher
     */
    public Flow.Publisher<ChatResponseUpdate> executeStreaming(
            ChatMiddlewareContext context, ChatStreamingMiddlewareNext terminal) {
        return invokeStreaming(0, context, AgentValidation.requireNonNull(terminal, "terminal"));
    }

    private CompletionStage<ChatResponse> invokeAsync(
            int index, ChatMiddlewareContext context, ChatMiddlewareNext terminal) {
        if (context.cancellation().isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (index >= middleware.size()) {
            return requireStage(terminal.invokeAsync(context));
        }
        AtomicBoolean invoked = new AtomicBoolean();
        ChatMiddlewareNext next = nextContext -> {
            if (!invoked.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new MiddlewareException("Chat middleware called next more than once."));
            }
            return invokeAsync(index + 1, AgentValidation.requireNonNull(nextContext, "context"), terminal);
        };
        try {
            return requireStage(middleware.get(index).invokeAsync(context, next));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private Flow.Publisher<ChatResponseUpdate> invokeStreaming(
            int index, ChatMiddlewareContext context, ChatStreamingMiddlewareNext terminal) {
        if (context.cancellation().isCancellationRequested()) {
            return MiddlewarePublishers.failed(new RunCancelledException());
        }
        if (index >= middleware.size()) {
            return requirePublisher(terminal.invokeStreaming(context));
        }
        AtomicBoolean invoked = new AtomicBoolean();
        ChatStreamingMiddlewareNext next = nextContext -> {
            if (!invoked.compareAndSet(false, true)) {
                return MiddlewarePublishers.failed(
                        new MiddlewareException("Chat middleware called next more than once."));
            }
            return invokeStreaming(index + 1, AgentValidation.requireNonNull(nextContext, "context"), terminal);
        };
        try {
            return requirePublisher(middleware.get(index).invokeStreaming(context, next));
        } catch (RuntimeException failure) {
            return MiddlewarePublishers.failed(failure);
        }
    }

    private static CompletionStage<ChatResponse> requireStage(CompletionStage<ChatResponse> stage) {
        return stage == null
                ? CompletableFuture.failedFuture(new MiddlewareException("Chat middleware returned a null stage."))
                : stage;
    }

    private static Flow.Publisher<ChatResponseUpdate> requirePublisher(Flow.Publisher<ChatResponseUpdate> publisher) {
        return publisher == null
                ? MiddlewarePublishers.failed(new MiddlewareException("Chat middleware returned a null publisher."))
                : publisher;
    }
}
