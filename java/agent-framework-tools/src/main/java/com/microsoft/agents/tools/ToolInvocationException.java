// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Indicates a framework or runtime failure while coordinating tool invocation.
 */
public class ToolInvocationException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an invocation exception.
     *
     * @param message invocation failure description
     */
    public ToolInvocationException(String message) {
        super(message);
    }

    /**
     * Creates an invocation exception with a cause.
     *
     * @param message invocation failure description
     * @param cause underlying cause
     */
    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
