// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Provides shared synchronous behavior for framework run handles.
 */
public final class RunHandles {
    private RunHandles() {}

    /**
     * Waits interruptibly for a run and preserves the framework cancellation contract.
     *
     * <p>Interruption requests cancellation, restores the current thread's interrupt status, and
     * throws {@link SynchronousExecutionException}. A terminal {@link RunCancelledException} is
     * propagated directly. Every other asynchronous failure is retained as the cause of a
     * {@link SynchronousExecutionException}.
     *
     * @param handle run handle
     * @param operation non-blank operation description used in exception messages
     * @param <T> terminal result type
     * @return terminal result
     * @throws RunCancelledException when cancellation wins
     * @throws SynchronousExecutionException when interrupted or asynchronous execution fails
     * @throws ValidationException when {@code operation} is blank
     */
    public static <T> T await(RunHandle<T> handle, String operation) {
        Objects.requireNonNull(handle, "handle");
        if (operation == null || operation.isBlank()) {
            throw new ValidationException("operation must not be blank.");
        }
        try {
            return handle.resultAsync().toCompletableFuture().get();
        } catch (InterruptedException exception) {
            handle.cancel();
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException(operation + " was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception.getCause());
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            throw new SynchronousExecutionException(operation + " failed.", cause);
        }
    }

    /**
     * Removes completion-wrapper exceptions while retaining the underlying typed failure.
     *
     * @param failure failure to inspect
     * @return innermost non-wrapper failure
     */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
