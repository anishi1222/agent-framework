// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/** Classifies invalid or undecodable structured agent output. */
public final class StructuredOutputException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a structured-output failure.
     *
     * @param message safe failure description
     * @param cause underlying parse or decode failure
     */
    public StructuredOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
