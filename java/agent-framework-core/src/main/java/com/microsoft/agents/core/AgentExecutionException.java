// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Classifies failures produced while executing an agent, chat, tool, or workflow run.
 */
public class AgentExecutionException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an execution exception with a message.
     *
     * @param message failure description
     */
    public AgentExecutionException(String message) {
        super(message);
    }

    /**
     * Creates an execution exception with a message and cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
