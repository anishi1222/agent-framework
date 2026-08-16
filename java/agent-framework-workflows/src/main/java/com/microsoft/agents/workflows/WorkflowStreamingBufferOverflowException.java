// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Indicates that a workflow event subscriber did not keep up with its bounded stream. */
public final class WorkflowStreamingBufferOverflowException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a streaming-buffer overflow exception.
     *
     * @param maximum configured maximum buffered event count
     */
    public WorkflowStreamingBufferOverflowException(int maximum) {
        super("Workflow event buffer exceeded maxBufferedEvents=" + maximum + ".");
    }
}
