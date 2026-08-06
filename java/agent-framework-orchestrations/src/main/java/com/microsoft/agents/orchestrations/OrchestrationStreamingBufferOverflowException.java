// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/** Indicates that a subscriber did not consume orchestration events within the configured bound. */
public final class OrchestrationStreamingBufferOverflowException extends OrchestrationExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an overflow failure for a configured event limit.
     *
     * @param maxBufferedEvents configured positive buffer limit
     */
    public OrchestrationStreamingBufferOverflowException(int maxBufferedEvents) {
        super("Orchestration event buffer exceeded maxBufferedEvents=" + maxBufferedEvents + ".");
    }
}
