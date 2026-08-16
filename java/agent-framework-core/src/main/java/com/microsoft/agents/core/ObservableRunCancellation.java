// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Extends {@link RunCancellation} with removable cancellation listeners.
 *
 * <p>Framework components use this optional contract to release per-operation callbacks when work
 * completes, instead of retaining callbacks for the lifetime of a long logical run.
 */
public interface ObservableRunCancellation extends RunCancellation {
    /**
     * Registers a listener that runs at most once when cancellation is requested.
     *
     * <p>If cancellation was already requested, the listener runs before this method returns and the
     * returned registration is already inactive.
     *
     * @param listener cancellation listener
     * @return removable registration
     */
    RunCancellationRegistration register(Runnable listener);
}
