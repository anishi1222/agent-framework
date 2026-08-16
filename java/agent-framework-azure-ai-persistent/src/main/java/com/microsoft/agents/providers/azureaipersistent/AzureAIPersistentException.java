// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import java.time.Duration;

/** Reports a sanitized persistent-agent service, transport, or protocol failure. */
public final class AzureAIPersistentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Identifies the failure category. */
    public enum Kind {
        /** Authentication failure. */
        AUTHENTICATION,
        /** Service rejected a valid transport request. */
        SERVICE,
        /** Network or client transport failure. */
        TRANSPORT,
        /** Service response violated the expected protocol. */
        PROTOCOL,
        /** Adapter configuration or unsupported capability. */
        CONFIGURATION
    }

    private final Kind kind;
    private final Integer statusCode;
    private final String requestId;
    private final String serviceCode;
    private final Duration retryAfter;

    /**
     * Creates a sanitized exception.
     *
     * @param message sanitized message
     * @param cause optional cause
     * @param kind failure category
     * @param statusCode optional HTTP status
     * @param requestId optional service request identifier
     * @param serviceCode optional service error code
     * @param retryAfter optional retry delay
     */
    public AzureAIPersistentException(
            String message,
            Throwable cause,
            Kind kind,
            Integer statusCode,
            String requestId,
            String serviceCode,
            Duration retryAfter) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
        this.requestId = safe(requestId);
        this.serviceCode = safe(serviceCode);
        this.retryAfter = retryAfter;
    }

    /** Returns the failure category. */
    public Kind kind() {
        return kind;
    }

    /** Returns the optional HTTP status. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns the optional service request identifier. */
    public String requestId() {
        return requestId;
    }

    /** Returns the optional service error code. */
    public String serviceCode() {
        return serviceCode;
    }

    /** Returns the optional service retry delay. */
    public Duration retryAfter() {
        return retryAfter;
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]", "").trim();
        return sanitized.isEmpty() ? null : sanitized.substring(0, Math.min(sanitized.length(), 256));
    }
}
