// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Reports that a workflow event value has no explicit JSON-shaped encoding. */
public final class WorkflowValueEncodingException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a workflow value encoding failure.
     *
     * @param message failure description
     */
    public WorkflowValueEncodingException(String message) {
        super(message);
    }
}
