// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

/** Indicates conflicting concurrent state writes for a key without a reducer. */
public final class StateConflictException extends WorkflowException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a state-conflict exception.
     *
     * @param key conflicting state key
     */
    public StateConflictException(String key) {
        super("Concurrent workflow branches wrote conflicting values for state key '" + key + "'.");
    }
}
