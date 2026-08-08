// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.AgentFrameworkException;

/** Serves as the root for A2A client, protocol, and hosting failures. */
public class A2AException extends AgentFrameworkException {
    private static final long serialVersionUID = 1L;

    /** Creates an A2A failure. */
    public A2AException(String message) {
        super(message);
    }

    /** Creates an A2A failure with an underlying cause. */
    public A2AException(String message, Throwable cause) {
        super(message, cause);
    }
}
