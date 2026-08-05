// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/**
 * Indicates a safe serialization, envelope, codec, version, or configured-limit failure.
 */
public class SerializationException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    private final SerializationError error;

    /**
     * Creates a serialization exception with a stable category.
     *
     * @param error failure category
     * @param message failure description
     */
    public SerializationException(SerializationError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * Creates a serialization exception with a stable category and cause.
     *
     * @param error failure category
     * @param message failure description
     * @param cause underlying cause
     */
    public SerializationException(SerializationError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * Returns the implementation-neutral failure category.
     *
     * @return failure category
     */
    public SerializationError error() {
        return error;
    }
}
