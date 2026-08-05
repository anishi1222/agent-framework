// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Serves as the root unchecked exception for Agent Framework failures.
 */
public class AgentFrameworkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message failure description
     */
    public AgentFrameworkException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public AgentFrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
