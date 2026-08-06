// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Provides cancellation-listener utilities for framework execution components.
 */
public final class RunCancellations {
    private RunCancellations() {}

    /**
     * Registers a removable listener for a cancellation signal.
     *
     * <p>Signals implementing {@link ObservableRunCancellation} release the listener from the
     * signal itself when the returned registration is closed. For other implementations, closing
     * the registration clears the callback from a holder retained by the cancellation stage and
     * suppresses later invocation.
     *
     * @param cancellation cancellation signal
     * @param listener callback invoked at most once after cancellation
     * @return removable registration
     */
    public static RunCancellationRegistration register(RunCancellation cancellation, Runnable listener) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(listener, "listener");
        if (cancellation instanceof ObservableRunCancellation observable) {
            return observable.register(listener);
        }

        FallbackRunCancellationRegistration registration = new FallbackRunCancellationRegistration(listener);
        cancellation.cancelledAsync().whenComplete((ignored, failure) -> registration.notifyCancellation());
        return registration;
    }
}
