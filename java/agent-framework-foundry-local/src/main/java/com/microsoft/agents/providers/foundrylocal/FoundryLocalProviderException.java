// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized Foundry Local REST failure.
 */
public final class FoundryLocalProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    private final Integer statusCode;

    private final String requestId;

    /** Creates a sanitized service failure. */
    public FoundryLocalProviderException(String kind, Integer statusCode, String requestId) {
        super("Foundry Local request failed (kind="
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
