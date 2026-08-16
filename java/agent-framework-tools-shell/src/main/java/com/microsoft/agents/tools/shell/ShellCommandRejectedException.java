// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

/** Indicates that a configured shell policy rejected a command before execution. */
public final class ShellCommandRejectedException extends ShellExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a policy-rejection exception.
     *
     * @param message rejection description
     */
    public ShellCommandRejectedException(String message) {
        super(message);
    }
}
