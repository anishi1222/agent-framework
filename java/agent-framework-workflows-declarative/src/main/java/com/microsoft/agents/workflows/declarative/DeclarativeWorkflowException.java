// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/** Indicates that a declarative workflow cannot be parsed, validated, or constructed. */
public class DeclarativeWorkflowException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative workflow exception.
     *
     * @param message failure description
     */
    public DeclarativeWorkflowException(String message) {
        super(message);
    }

    /**
     * Creates a declarative workflow exception with a cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public DeclarativeWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
