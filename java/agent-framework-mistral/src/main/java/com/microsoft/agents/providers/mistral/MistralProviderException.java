// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized Mistral transport or protocol failure.
 */
public final class MistralProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    private final Integer statusCode;

    private final String requestId;

    private final String providerCode;

    /**
     * Creates a sanitized provider failure.
     *
     * @param kind stable failure kind
     * @param statusCode optional HTTP status
     * @param requestId optional provider request identifier
     * @param providerCode optional provider error code
     */
    public MistralProviderException(String kind, Integer statusCode, String requestId, String providerCode) {
        super(message(kind, statusCode, requestId, providerCode));
        this.kind = require(kind, "kind");
        this.statusCode = statusCode;
        this.requestId = optional(requestId);
        this.providerCode = optional(providerCode);
    }

    /** Returns the stable failure kind. */
    public String kind() {
        return kind;
    }

    /** Returns the optional HTTP status. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns the optional provider request identifier. */
    public String requestId() {
        return requestId;
    }

    /** Returns the optional provider error code. */
    public String providerCode() {
        return providerCode;
    }

    private static String message(String kind, Integer status, String requestId, String code) {
        return "Mistral request failed (kind="
                + require(kind, "kind")
                + ", status="
                + (status == null ? "unknown" : status)
                + ", requestId="
                + (optional(requestId) == null ? "unknown" : requestId)
                + ", code="
                + (optional(code) == null ? "unknown" : code)
                + ").";
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
