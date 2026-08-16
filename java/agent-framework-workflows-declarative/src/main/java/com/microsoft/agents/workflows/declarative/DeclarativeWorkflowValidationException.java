// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/** Indicates that a declarative workflow graph or registry reference is invalid. */
public final class DeclarativeWorkflowValidationException extends DeclarativeWorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative workflow validation exception.
     *
     * @param message failure description
     */
    public DeclarativeWorkflowValidationException(String message) {
        super(message);
    }

    /**
     * Creates a declarative workflow validation exception with a cause.
     *
     * @param message failure description
     * @param cause underlying validation failure
     */
    public DeclarativeWorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
