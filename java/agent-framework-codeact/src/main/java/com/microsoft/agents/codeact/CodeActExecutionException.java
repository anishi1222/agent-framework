// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import com.microsoft.agents.core.AgentExecutionException;

/** Reports an invalid or failed framework-level CodeAct execution integration. */
public final class CodeActExecutionException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a CodeAct execution exception.
     *
     * @param message failure description
     */
    public CodeActExecutionException(String message) {
        super(message);
    }

    /**
     * Creates a CodeAct execution exception with a cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public CodeActExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
