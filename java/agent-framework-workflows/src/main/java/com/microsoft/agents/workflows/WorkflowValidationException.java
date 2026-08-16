// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Indicates that a workflow graph violates a construction or type-safety rule. */
public final class WorkflowValidationException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a workflow validation exception.
     *
     * @param message validation failure description
     */
    public WorkflowValidationException(String message) {
        super(message);
    }
}
