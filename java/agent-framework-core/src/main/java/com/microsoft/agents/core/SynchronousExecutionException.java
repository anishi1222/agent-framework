// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Wraps a typed asynchronous failure observed through a synchronous facade.
 */
public class SynchronousExecutionException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a synchronous wrapper retaining the typed cause.
     *
     * @param message failure description
     * @param cause typed asynchronous failure or interruption
     */
    public SynchronousExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
