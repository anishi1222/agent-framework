// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentFrameworkException;

/** Indicates an exceptional orchestration-runtime failure. */
public class OrchestrationExecutionException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an orchestration exception.
     *
     * @param message failure description
     */
    public OrchestrationExecutionException(String message) {
        super(message);
    }

    /**
     * Creates an orchestration exception with a cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public OrchestrationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
