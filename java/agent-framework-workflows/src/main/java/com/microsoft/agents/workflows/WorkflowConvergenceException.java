// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Indicates that a workflow stopped without output or exceeded its superstep guard. */
public final class WorkflowConvergenceException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a workflow convergence exception.
     *
     * @param message convergence failure description
     */
    public WorkflowConvergenceException(String message) {
        super(message);
    }
}
