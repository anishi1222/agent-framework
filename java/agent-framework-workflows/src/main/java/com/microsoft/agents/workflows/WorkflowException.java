// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.AgentExecutionException;

/** Indicates a failure while validating, executing, or restoring a workflow. */
public class WorkflowException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a workflow exception.
     *
     * @param message failure description
     */
    public WorkflowException(String message) {
        super(message);
    }

    /**
     * Creates a workflow exception with an underlying cause.
     *
     * @param message failure description
     * @param cause underlying cause
     */
    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
