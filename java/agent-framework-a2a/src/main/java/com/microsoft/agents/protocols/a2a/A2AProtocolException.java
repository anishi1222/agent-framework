// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a JSON-RPC or A2A protocol error without discarding unknown wire codes.
 *
 * <p>Standard codes are available through {@link #errorCode()}; future and application-defined
 * codes remain actionable through {@link #rawErrorCode()}. Structured data is retained for explicit
 * inspection but is not included in exception diagnostics.
 */
public class A2AProtocolException extends A2AException {
    private static final long serialVersionUID = 1L;

    private final A2AErrorCode errorCode;

    private final int rawErrorCode;

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
        this(Objects.requireNonNull(errorCode, "errorCode").code(), message, data, cause);
    }

    /**
     * Creates a protocol error from an arbitrary wire code.
     *
     * @param rawErrorCode exact JSON-RPC wire code
     * @param message server-provided or locally sanitized description
     * @param data optional JSON-shaped detail
     * @param cause underlying cause
     */
    public A2AProtocolException(int rawErrorCode, String message, StateValue data, Throwable cause) {
        super(A2AValidation.nonBlank(message, "message"), cause);
        this.rawErrorCode = rawErrorCode;
        this.errorCode = A2AErrorCode.fromCode(rawErrorCode).orElse(null);
        this.data = data;
    }

    /**
     * Returns the typed standard error code when known.
     *
     * @return standard code, or empty for future and application-defined codes
     */
    public Optional<A2AErrorCode> errorCode() {
        return Optional.ofNullable(errorCode);
    }

    /**
     * Returns the exact JSON-RPC wire error code.
     *
     * @return raw integer code
     */
    public int rawErrorCode() {
        return rawErrorCode;
    }

    /** Returns optional structured detail. */
    public StateValue data() {
        return data;
    }
}
