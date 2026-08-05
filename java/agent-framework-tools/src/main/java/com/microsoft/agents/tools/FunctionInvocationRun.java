// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.SynchronousExecutionException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Exposes synchronous, asynchronous, and streaming views of one shared loop execution owner.
 */
public final class FunctionInvocationRun {
    private final RunCancellation cancellation;

    private final SingleSubscriberPublisher<ChatResponseUpdate> updates;

    private final java.util.concurrent.CompletableFuture<FunctionLoopResult> result =
            new java.util.concurrent.CompletableFuture<>();

    private final CompletionStage<FunctionLoopResult> resultView = result.minimalCompletionStage();

    private final AtomicBoolean started = new AtomicBoolean();

    private final Function<SingleSubscriberPublisher<ChatResponseUpdate>, CompletionStage<FunctionLoopResult>>
            execution;

    FunctionInvocationRun(
            RunCancellation cancellation,
            Function<SingleSubscriberPublisher<ChatResponseUpdate>, CompletionStage<FunctionLoopResult>> execution) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.updates = new SingleSubscriberPublisher<>(this::start, cancellation::cancel);
    }

    /**
     * Returns the shared read-only terminal result stage.
     *
     * @return terminal result stage
     */
    public CompletionStage<FunctionLoopResult> resultAsync() {
        start();
        return resultView;
    }

    /**
     * Waits interruptibly for the shared terminal result.
     *
     * @return terminal result
     * @throws RunCancelledException when cancellation wins
     * @throws SynchronousExecutionException when interrupted or execution fails
     */
    public FunctionLoopResult result() {
        start();
        try {
            return result.get();
        } catch (InterruptedException exception) {
            cancel();
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException("Function loop was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception.getCause());
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            throw new SynchronousExecutionException("Function loop failed.", cause);
        }
    }

    /**
     * Returns the single-subscriber update publisher for this same execution owner.
     *
     * @return update publisher
     */
    public Flow.Publisher<ChatResponseUpdate> updates() {
        return updates;
    }

    /**
     * Returns the run cancellation signal.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return cancellation;
    }

    /**
     * Requests cancellation exactly once.
     *
     * @return {@code true} only when this request initiated cancellation
     */
    public boolean cancel() {
        return cancellation.cancel();
    }

    private void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (cancellation.isCancellationRequested()) {
            RunCancelledException cancelled = new RunCancelledException();
            result.completeExceptionally(cancelled);
            updates.fail(cancelled);
            return;
        }
        CompletionStage<FunctionLoopResult> stage;
        try {
            stage = execution.apply(updates);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            updates.fail(failure);
            return;
        }
        if (stage == null) {
            ToolInvocationException failure =
                    new ToolInvocationException("Function loop execution returned a null CompletionStage.");
            result.completeExceptionally(failure);
            updates.fail(failure);
            return;
        }
        stage.whenComplete((value, failure) -> {
            if (failure != null) {
                Throwable cause = unwrap(failure);
                result.completeExceptionally(cause);
                updates.fail(cause);
            } else {
                result.complete(value);
                updates.complete();
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
