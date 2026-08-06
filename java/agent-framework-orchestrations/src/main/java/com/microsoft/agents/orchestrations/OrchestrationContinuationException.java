// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Indicates that an orchestration continuation is unsupported, invalid, stale, or already consumed. */
public final class OrchestrationContinuationException extends OrchestrationExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a continuation failure.
     *
     * @param message failure description
     */
    public OrchestrationContinuationException(String message) {
        super(message);
    }
}
