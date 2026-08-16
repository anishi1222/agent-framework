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

    private final RunCancellationListeners cancellationListeners = new RunCancellationListeners();

    private final RunCancellation delegate;

    private final RunCancellation cancellation = new SourceCancellation();

    private final RunHandle<T> handle = new SourceHandle();

    private RunCancellationRegistration upstreamCancellationRegistration = () -> {};

    /** Creates a source with a framework-owned cancellation signal. */
    public RunHandleSource() {
        this(new DefaultRunCancellation());
    }

    /**
     * Creates a source linked to a caller-owned cancellation signal.
     *
     * @param cancellation caller-owned cancellation signal
     */
    public RunHandleSource(RunCancellation cancellation) {
        this.delegate = Objects.requireNonNull(cancellation, "cancellation");
        upstreamCancellationRegistration = RunCancellations.register(delegate, this::cancelRun);
        if (delegate.isCancellationRequested()) {
            cancelRun();
        }
    }

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
        cancellationListeners.clear();
        upstreamCancellationRegistration.close();
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
        cancellationListeners.clear();
        upstreamCancellationRegistration.close();
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
        cancelled.complete(null);
        cancellationListeners.notifyCancellation();
        result.completeExceptionally(new RunCancelledException());
        return true;
    }

    private enum TerminalState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final class SourceCancellation implements ObservableRunCancellation {
        @Override
        public boolean cancel() {
            if (state.get() != TerminalState.RUNNING) {
                return false;
            }
            boolean initiated = delegate.cancel();
            if (initiated || delegate.isCancellationRequested()) {
                cancelRun();
            }
            return initiated && state.get() == TerminalState.CANCELLED;
        }

        @Override
        public boolean isCancellationRequested() {
            return state.get() == TerminalState.CANCELLED;
        }

        @Override
        public CompletionStage<Void> cancelledAsync() {
            return cancelledView;
        }

        @Override
        public RunCancellationRegistration register(Runnable listener) {
            return cancellationListeners.register(
                    () -> state.get() != TerminalState.RUNNING, () -> state.get() == TerminalState.CANCELLED, listener);
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
