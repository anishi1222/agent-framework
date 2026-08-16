// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized Copilot Studio authentication, transport, protocol, or service failure.
 */
public final class CopilotStudioException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final Kind kind;

    private final Integer statusCode;

    private final String code;

    /**
     * Creates a sanitized failure.
     *
     * @param message non-secret message
     * @param cause optional cause
     * @param kind category
     * @param statusCode optional HTTP status
     * @param code optional stable code
     */
    public CopilotStudioException(String message, Throwable cause, Kind kind, Integer statusCode, String code) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
        this.code = code == null || code.isBlank() ? null : code;
    }

    /** Returns the failure category. */
    public Kind kind() {
        return kind;
    }

    /** Returns the optional HTTP status. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns the optional stable code. */
    public String code() {
        return code;
    }

    /** Failure categories. */
    public enum Kind {
        /** Invalid caller configuration. */
        CONFIGURATION,
        /** Token acquisition or token lifetime failure. */
        AUTHENTICATION,
        /** HTTP or stream transport failure. */
        TRANSPORT,
        /** Invalid or incompatible protocol data. */
        PROTOCOL,
        /** Non-success service response. */
        SERVICE,
        /** Configured resource bound was exceeded. */
        LIMIT
    }
}
