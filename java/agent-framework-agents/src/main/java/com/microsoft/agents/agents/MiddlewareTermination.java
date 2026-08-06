// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

/**
 * Explicitly terminates a middleware pipeline without invoking later work.
 *
 * <p>Use a normal valid return value for result-bearing short circuits. This exception is intended
 * for termination where no result is available; it propagates unless an application boundary chooses
 * to translate it.
 */
public final class MiddlewareTermination extends MiddlewareException {
    private static final long serialVersionUID = 1L;

    /** Creates a termination with the standard message. */
    public MiddlewareTermination() {
        super("Middleware terminated execution.");
    }

    /**
     * Creates a termination with a description.
     *
     * @param message termination description
     */
    public MiddlewareTermination(String message) {
        super(message);
    }
}
