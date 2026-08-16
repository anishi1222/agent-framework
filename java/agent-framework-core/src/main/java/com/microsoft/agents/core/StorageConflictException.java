// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Indicates that an optimistic storage revision did not match.
 */
public class StorageConflictException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a storage conflict exception.
     *
     * @param message conflict description
     */
    public StorageConflictException(String message) {
        super(message);
    }

    /**
     * Creates a storage conflict exception with a cause.
     *
     * @param message conflict description
     * @param cause underlying storage failure
     */
    public StorageConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
