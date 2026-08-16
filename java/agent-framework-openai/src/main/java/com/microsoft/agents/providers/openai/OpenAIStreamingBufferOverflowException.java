// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Indicates that an OpenAI streaming subscriber did not consume updates within the configured
 * bounded capacity.
 */
public final class OpenAIStreamingBufferOverflowException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a bounded-stream overflow failure.
     *
     * @param maxBufferedUpdates configured positive buffer capacity
     */
    public OpenAIStreamingBufferOverflowException(int maxBufferedUpdates) {
        super("OpenAI streaming update buffer exceeded maxBufferedUpdates=" + maxBufferedUpdates + ".");
    }
}
