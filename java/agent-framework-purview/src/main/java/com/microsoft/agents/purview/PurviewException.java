// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.time.Duration;

/** Reports a sanitized Microsoft Purview policy service failure. */
public final class PurviewException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Failure category. */
    public enum Kind {
        /** Authentication or authorization failure. */
        AUTHENTICATION,
        /** Licensing or billing failure. */
        PAYMENT_REQUIRED,
        /** Service throttling. */
        RATE_LIMIT,
        /** Other service rejection. */
        SERVICE,
        /** Network transport failure. */
        TRANSPORT,
        /** Malformed service response. */
        PROTOCOL,
        /** Invalid policy integration configuration. */
        CONFIGURATION
    }

    private final Kind kind;
    private final Integer statusCode;
    private final String requestId;
    private final String serviceCode;
    private final Duration retryAfter;

    /** Creates a sanitized Purview exception. */
    public PurviewException(
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

    /** Returns the optional request identifier. */
    public String requestId() {
        return requestId;
    }

    /** Returns the optional service code. */
    public String serviceCode() {
        return serviceCode;
    }

    /** Returns the optional retry delay. */
    public Duration retryAfter() {
        return retryAfter;
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("[\\r\\n\\t]", "").trim();
        return clean.isEmpty() ? null : clean.substring(0, Math.min(clean.length(), 256));
    }
}
