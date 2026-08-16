// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Indicates a sanitized AG-UI protocol, validation, or transport failure. */
public final class AGUIProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final AGUIErrorCode code;

    /**
     * Creates a protocol exception.
     *
     * @param code stable error class
     * @param message sanitized message
     */
    public AGUIProtocolException(AGUIErrorCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    /**
     * Creates a protocol exception with an internal cause.
     *
     * @param code stable error class
     * @param message sanitized message
     * @param cause internal cause
     */
    public AGUIProtocolException(AGUIErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    /**
     * Returns the stable error class.
     *
     * @return error code
     */
    public AGUIErrorCode code() {
        return code;
    }
}
