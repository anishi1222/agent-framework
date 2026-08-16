// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

/** Indicates that a declarative workflow document is malformed or violates its strict schema. */
public final class DeclarativeWorkflowParseException extends DeclarativeWorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative workflow parse exception.
     *
     * @param message failure description
     */
    public DeclarativeWorkflowParseException(String message) {
        super(message);
    }

    /**
     * Creates a declarative workflow parse exception with a cause.
     *
     * @param message failure description
     * @param cause underlying parser failure
     */
    public DeclarativeWorkflowParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
