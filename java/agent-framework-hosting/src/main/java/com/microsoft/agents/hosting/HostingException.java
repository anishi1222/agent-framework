// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

/** Represents a typed hosting boundary failure safe for transport mapping. */
public class HostingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient HostingError error;

    /**
     * Creates a typed failure.
     *
     * @param code stable code
     * @param message safe client-facing message
     */
    public HostingException(HostingErrorCode code, String message) {
        this(new HostingError(code, message, retryable(code), java.util.Map.of()), null);
    }

    /**
     * Creates a typed failure retaining a server-side cause.
     *
     * @param code stable code
     * @param message safe client-facing message
     * @param cause internal cause, never serialized
     */
    public HostingException(HostingErrorCode code, String message, Throwable cause) {
        this(new HostingError(code, message, retryable(code), java.util.Map.of()), cause);
    }

    /**
     * Creates a failure from an immutable sanitized error.
     *
     * @param error sanitized error
     */
    public HostingException(HostingError error) {
        this(error, null);
    }

    private HostingException(HostingError error, Throwable cause) {
        super(java.util.Objects.requireNonNull(error, "error").message(), cause);
        this.error = error;
    }

    /**
     * Returns the safe transport error.
     *
     * @return error
     */
    public HostingError error() {
        return error;
    }

    private static boolean retryable(HostingErrorCode code) {
        return code == HostingErrorCode.TOO_MANY_REQUESTS
                || code == HostingErrorCode.OVERFLOW
                || code == HostingErrorCode.RUN_TIMEOUT;
    }
}
