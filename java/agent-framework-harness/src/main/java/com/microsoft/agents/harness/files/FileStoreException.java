// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness.files;

import com.microsoft.agents.core.AgentFrameworkException;

/** Signals an explicit harness file-store failure. */
public final class FileStoreException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a file-store failure.
     *
     * @param message failure description
     */
    public FileStoreException(String message) {
        super(message);
    }

    /**
     * Creates a file-store failure.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public FileStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
