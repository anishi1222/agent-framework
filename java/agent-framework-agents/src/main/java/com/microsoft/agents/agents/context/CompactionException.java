// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.core.AgentExecutionException;

/** Reports a framework compaction failure. */
public class CompactionException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a compaction failure.
     *
     * @param message safe failure description
     */
    public CompactionException(String message) {
        super(message);
    }

    /**
     * Creates a compaction failure with a cause.
     *
     * @param message safe failure description
     * @param cause underlying failure
     */
    public CompactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
