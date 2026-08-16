// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2AException;

/** Reports an authentication or authorization rejection without exposing credentials. */
public final class A2AAuthenticationException extends A2AException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    /**
     * Creates an authentication rejection.
     *
     * @param statusCode HTTP 401 or 403
     * @param message sanitized message
     */
    public A2AAuthenticationException(int statusCode, String message) {
        super(message);
        if (statusCode != 401 && statusCode != 403) {
            throw new IllegalArgumentException("Authentication status must be 401 or 403.");
        }
        this.statusCode = statusCode;
    }

    /** Returns HTTP 401 or 403. */
    public int statusCode() {
        return statusCode;
    }
}
