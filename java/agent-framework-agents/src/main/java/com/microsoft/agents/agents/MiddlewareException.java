// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentFrameworkException;

/** Indicates invalid middleware behavior or pipeline configuration. */
public class MiddlewareException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a middleware exception.
     *
     * @param message failure description
     */
    public MiddlewareException(String message) {
        super(message);
    }

    /**
     * Creates a middleware exception with a cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public MiddlewareException(String message, Throwable cause) {
        super(message, cause);
    }
}
