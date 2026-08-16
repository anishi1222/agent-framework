// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

/** Indicates that a declarative agent definition or one of its references is invalid. */
public final class DeclarativeAgentValidationException extends DeclarativeAgentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative agent validation exception.
     *
     * @param message failure description
     */
    public DeclarativeAgentValidationException(String message) {
        super(message);
    }

    /**
     * Creates a declarative agent validation exception with a cause.
     *
     * @param message failure description
     * @param cause underlying validation failure
     */
    public DeclarativeAgentValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
