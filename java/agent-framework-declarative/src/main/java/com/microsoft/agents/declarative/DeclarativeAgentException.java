// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

/** Indicates that a declarative agent document cannot be parsed, validated, or constructed. */
public class DeclarativeAgentException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative agent exception.
     *
     * @param message failure description
     */
    public DeclarativeAgentException(String message) {
        super(message);
    }

    /**
     * Creates a declarative agent exception with a cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public DeclarativeAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
