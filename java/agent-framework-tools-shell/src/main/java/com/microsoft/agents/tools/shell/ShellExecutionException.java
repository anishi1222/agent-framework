// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

/** Indicates a shell launch, protocol, or lifecycle failure. */
public class ShellExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a shell execution failure.
     *
     * @param message failure description
     */
    public ShellExecutionException(String message) {
        super(message);
    }

    /**
     * Creates a shell execution failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public ShellExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
