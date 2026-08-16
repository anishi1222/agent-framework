// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Indicates that a run reached its terminal state because cancellation was requested.
 */
public class RunCancelledException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /** Creates a cancellation exception with the standard message. */
    public RunCancelledException() {
        super("The run was cancelled.");
    }

    /**
     * Creates a cancellation exception with a message.
     *
     * @param message cancellation description
     */
    public RunCancelledException(String message) {
        super(message);
    }

    /**
     * Creates a cancellation exception with a message and cause.
     *
     * @param message cancellation description
     * @param cause underlying cancellation cause
     */
    public RunCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
