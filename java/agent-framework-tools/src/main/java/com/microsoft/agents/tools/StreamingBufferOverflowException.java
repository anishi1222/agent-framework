// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

/**
 * Indicates that a run produced more streaming updates than its configured finite buffer permits.
 */
public final class StreamingBufferOverflowException extends ToolInvocationException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a streaming-buffer overflow exception.
     *
     * @param maxBufferedUpdates configured update limit
     */
    public StreamingBufferOverflowException(int maxBufferedUpdates) {
        super("Streaming update buffer exceeded maxBufferedUpdates=" + maxBufferedUpdates + ".");
    }
}
