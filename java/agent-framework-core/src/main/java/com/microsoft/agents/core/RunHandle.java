// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.concurrent.CompletionStage;

/**
 * Exposes the terminal result and cancellation controller for one run.
 *
 * @param <T> terminal result type
 */
public interface RunHandle<T> {
    /**
     * Returns the read-only terminal result stage.
     *
     * @return result stage
     */
    CompletionStage<T> resultAsync();

    /**
     * Returns the cancellation controller owned by this run.
     *
     * @return cancellation controller
     */
    RunCancellation cancellation();

    /**
     * Delegates cancellation to {@link #cancellation()}.
     *
     * @return {@code true} only when this request initiated cancellation
     */
    default boolean cancel() {
        return cancellation().cancel();
    }
}
