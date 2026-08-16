// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Indicates that a workflow checkpoint could not be encoded, stored, or restored. */
public final class WorkflowCheckpointException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a checkpoint exception.
     *
     * @param message checkpoint failure description
     */
    public WorkflowCheckpointException(String message) {
        super(message);
    }

    /**
     * Creates a checkpoint exception with an underlying cause.
     *
     * @param message checkpoint failure description
     * @param cause underlying cause
     */
    public WorkflowCheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
