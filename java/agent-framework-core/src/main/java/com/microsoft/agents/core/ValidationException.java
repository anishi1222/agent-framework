// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Indicates that a framework-owned value or operation violates its public contract.
 */
public class ValidationException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a validation exception with a message.
     *
     * @param message validation failure description
     */
    public ValidationException(String message) {
        super(message);
    }

    /**
     * Creates a validation exception with a message and cause.
     *
     * @param message validation failure description
     * @param cause underlying cause
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
