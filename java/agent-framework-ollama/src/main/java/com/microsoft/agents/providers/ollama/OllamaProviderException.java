// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized Ollama transport or protocol failure.
 */
public final class OllamaProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    private final Integer statusCode;

    private final String requestId;

    /** Creates a sanitized provider failure. */
    public OllamaProviderException(String kind, Integer statusCode, String requestId) {
        super("Ollama request failed (kind="
                + valid(kind)
                + ", status="
                + (statusCode == null ? "unknown" : statusCode)
                + ", requestId="
                + (requestId == null || requestId.isBlank() ? "unknown" : requestId)
                + ").");
        this.kind = valid(kind);
        this.statusCode = statusCode;
        this.requestId = requestId == null || requestId.isBlank() ? null : requestId;
    }

    /** Returns the stable failure kind. */
    public String kind() {
        return kind;
    }

    /** Returns the optional HTTP status. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns the optional request identifier. */
    public String requestId() {
        return requestId;
    }

    private static String valid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank.");
        }
        return value;
    }
}
