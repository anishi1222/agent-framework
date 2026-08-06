// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentFrameworkException;

/** Indicates that another run currently owns the mutable gate for an agent session. */
public final class SessionBusyException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a session-busy exception.
     *
     * @param message failure description
     */
    public SessionBusyException(String message) {
        super(message);
    }
}
