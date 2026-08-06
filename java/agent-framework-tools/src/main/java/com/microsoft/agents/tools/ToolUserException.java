// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Indicates an expected, user-correctable failure reported by a tool body.
 *
 * <p>The function loop converts this exception into one sanitized, correlated error result. Tool
 * implementations should throw this type only for recoverable input or domain validation failures.
 * Unexpected implementation failures, framework exceptions, cancellation, and {@link Error} values
 * propagate instead of being presented to the model as recoverable tool output.
 */
public class ToolUserException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a recoverable tool-user exception.
     *
     * @param message user-correctable failure description
     */
    public ToolUserException(String message) {
        super(message);
    }

    /**
     * Creates a recoverable tool-user exception with a cause.
     *
     * @param message user-correctable failure description
     * @param cause underlying cause
     */
    public ToolUserException(String message, Throwable cause) {
        super(message, cause);
    }
}
