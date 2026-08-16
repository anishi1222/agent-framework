// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

/** Indicates that a declarative agent document is malformed or violates its strict schema. */
public final class DeclarativeAgentParseException extends DeclarativeAgentException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a declarative agent parse exception.
     *
     * @param message failure description
     */
    public DeclarativeAgentParseException(String message) {
        super(message);
    }

    /**
     * Creates a declarative agent parse exception with a cause.
     *
     * @param message failure description
     * @param cause underlying parser failure
     */
    public DeclarativeAgentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
