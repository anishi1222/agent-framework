// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import com.microsoft.agents.core.AgentExecutionException;

/**
 * Reports a sanitized Anthropic SDK or service failure.
 */
public final class AnthropicProviderException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final String kind;

    private final Integer statusCode;

    private final String requestId;

    private final String providerCode;

    /** Creates a sanitized provider failure. */
    public AnthropicProviderException(String kind, Integer statusCode, String requestId, String providerCode) {
        super("Anthropic request failed (kind="
                + valid(kind)
                + ", status="
                + (statusCode == null ? "unknown" : statusCode)
                + ", requestId="
                + display(requestId)
                + ", code="
                + display(providerCode)
                + ").");
        this.kind = valid(kind);
        this.statusCode = statusCode;
        this.requestId = optional(requestId);
        this.providerCode = optional(providerCode);
    }

    /** Returns the stable failure kind. */
    public String kind() {
        return kind;
    }

    /** Returns the optional status. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns the optional request identifier. */
    public String requestId() {
        return requestId;
    }

    /** Returns the optional provider code. */
    public String providerCode() {
        return providerCode;
    }

    private static String display(String value) {
        return optional(value) == null ? "unknown" : value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String valid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank.");
        }
        return value;
    }
}
