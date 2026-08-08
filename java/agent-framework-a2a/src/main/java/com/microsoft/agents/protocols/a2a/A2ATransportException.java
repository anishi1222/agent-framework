// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/** Represents HTTP, media-type, timeout, or stream-framing failure. */
public final class A2ATransportException extends A2AException {
    private static final long serialVersionUID = 1L;

    /** Creates a transport failure. */
    public A2ATransportException(String message) {
        super(message);
    }

    /** Creates a transport failure with an underlying cause. */
    public A2ATransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
