// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Represents a removable cancellation-listener registration.
 *
 * <p>Closing a registration is idempotent and prevents a listener that has not yet run from being
 * retained or invoked.
 */
@FunctionalInterface
public interface RunCancellationRegistration extends AutoCloseable {
    /**
     * Removes the registered listener.
     */
    @Override
    void close();
}
