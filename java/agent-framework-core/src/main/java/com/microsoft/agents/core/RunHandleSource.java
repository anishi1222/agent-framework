// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Separates execution-core ownership of run completion from caller access to a {@link RunHandle}.
 *
 * <p>The execution core retains this source and calls {@link #tryComplete(Object)} or
 * {@link #tryFail(Throwable)}. Callers receive only {@link #handle()}, which can observe the result
 * and request cancellation. Exactly one of success, failure, or cancellation wins atomically.
 *
 * @param <T> terminal result type
 */
public final class RunHandleSource<T> {
    private final AtomicReference<TerminalState> state = new AtomicReference<>(TerminalState.RUNNING);

    private final CompletableFuture<T> result = new CompletableFuture<>();

    private final CompletableFuture<Void> cancelled = new CompletableFuture<>();

    private final CompletionStage<T> resultView = result.minimalCompletionStage();

    private final CompletionStage<Void> cancelledView = cancelled.minimalCompletionStage();

    private final RunCancellation cancellation = new SourceCancellation();

    private final RunHandle<T> handle = new SourceHandle();

    /**
     * Returns the caller-facing view of this run.
     *
     * @return stable run handle
     */
    public RunHandle<T> handle() {
        return handle;
    }

    /**
     * Returns the execution-core cancellation signal.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return cancellation;
    }

    /**
     * Attempts to complete the run successfully.
     *
     * @param value non-null terminal value
     * @return {@code true} only when success won the terminal race
     */
    public boolean tryComplete(T value) {
        Objects.requireNonNull(value, "value");
        if (!state.compareAndSet(TerminalState.RUNNING, TerminalState.SUCCEEDED)) {
            return false;
        }
        result.complete(value);
        return true;
    }

    /**
     * Attempts to complete the run with a failure.
     *
     * @param failure non-null failure
     * @return {@code true} only when failure won the terminal race
     */
    public boolean tryFail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (!state.compareAndSet(TerminalState.RUNNING, TerminalState.FAILED)) {
            return false;
        }
        result.completeExceptionally(failure);
        return true;
    }

    /**
     * Reports whether this source has reached any terminal state.
     *
     * @return {@code true} after success, failure, or cancellation
     */
    public boolean isTerminal() {
        return state.get() != TerminalState.RUNNING;
    }

    private boolean cancelRun() {
        if (!state.compareAndSet(TerminalState.RUNNING, TerminalState.CANCELLED)) {
            return false;
        }
        result.completeExceptionally(new RunCancelledException());
        cancelled.complete(null);
        return true;
    }

    private enum TerminalState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final class SourceCancellation implements RunCancellation {
        @Override
        public boolean cancel() {
            return cancelRun();
        }

        @Override
        public boolean isCancellationRequested() {
            return state.get() == TerminalState.CANCELLED;
        }

        @Override
        public CompletionStage<Void> cancelledAsync() {
            return cancelledView;
        }
    }

    private final class SourceHandle implements RunHandle<T> {
        @Override
        public CompletionStage<T> resultAsync() {
            return resultView;
        }

        @Override
        public RunCancellation cancellation() {
            return cancellation;
        }
    }
}
