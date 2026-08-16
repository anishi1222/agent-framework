// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.concurrent.CompletionStage;

/**
 * Controls and observes cancellation for one framework run.
 *
 * <p>Implementations that support removable listeners should also implement {@link
 * ObservableRunCancellation}. This lets long-running framework operations release completed
 * per-operation callbacks without changing this core contract.
 */
public interface RunCancellation {
    /**
     * Requests cancellation exactly once.
     *
     * @return {@code true} only for the request that transitioned to cancelled
     */
    boolean cancel();

    /**
     * Reports whether cancellation was requested.
     *
     * @return {@code true} after a successful cancellation request
     */
    boolean isCancellationRequested();

    /**
     * Completes normally when cancellation is first requested.
     *
     * @return read-only cancellation notification
     */
    CompletionStage<Void> cancelledAsync();
}
