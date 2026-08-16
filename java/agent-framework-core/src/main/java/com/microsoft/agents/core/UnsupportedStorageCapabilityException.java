// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Indicates that a requested storage operation requires an unadvertised capability.
 */
public class UnsupportedStorageCapabilityException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an unsupported-capability exception.
     *
     * @param message capability failure description
     */
    public UnsupportedStorageCapabilityException(String message) {
        super(message);
    }
}
