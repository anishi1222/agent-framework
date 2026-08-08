// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;

/** Represents a typed JSON-RPC or A2A protocol error. */
public class A2AProtocolException extends A2AException {
    private static final long serialVersionUID = 1L;
    private final A2AErrorCode errorCode;
    private final transient StateValue data;

    /**
     * Creates a protocol error without data.
     *
     * @param errorCode typed error
     * @param message sanitized description
     */
    public A2AProtocolException(A2AErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    /**
     * Creates a protocol error.
     *
     * @param errorCode typed error
     * @param message sanitized description
     * @param data optional JSON-shaped detail
     * @param cause underlying cause
     */
    public A2AProtocolException(A2AErrorCode errorCode, String message, StateValue data, Throwable cause) {
        super(A2AValidation.nonBlank(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.data = data;
    }

    /** Returns the typed error code. */
    public A2AErrorCode errorCode() {
        return errorCode;
    }

    /** Returns optional structured detail. */
    public StateValue data() {
        return data;
    }
}
