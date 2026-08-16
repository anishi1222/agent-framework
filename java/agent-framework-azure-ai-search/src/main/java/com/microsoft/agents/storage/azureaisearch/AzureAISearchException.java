// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.AgentExecutionException;
import java.time.Duration;
import java.util.Objects;

/** Reports a sanitized Azure AI Search transport, service, or response-contract failure. */
public final class AzureAISearchException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final Kind kind;

    private final String operation;

    private final Integer statusCode;

    private final String requestId;

    private final Duration retryAfter;

    AzureAISearchException(
            Kind kind, String operation, Integer statusCode, String requestId, Duration retryAfter, Throwable cause) {
        super(message(kind, operation, statusCode), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    /** Returns the failure category. */
    public Kind kind() {
        return kind;
    }

    /** Returns the stable operation name without request data. */
    public String operation() {
        return operation;
    }

    /** Returns the service status when available. */
    public Integer statusCode() {
        return statusCode;
    }

    /** Returns a sanitized bounded request identifier when available. */
    public String requestId() {
        return requestId;
    }

    /** Returns the service retry delay when available. */
    public Duration retryAfter() {
        return retryAfter;
    }

    boolean continuable() {
        if (kind == Kind.TRANSPORT || kind == Kind.TIMEOUT) {
            return true;
        }
        return kind == Kind.SERVICE
                && statusCode != null
                && (statusCode == 408 || statusCode == 429 || statusCode >= 500 && statusCode < 600);
    }

    private static String message(Kind kind, String operation, Integer statusCode) {
        String suffix = statusCode == null ? "" : ", status=" + statusCode;
        return "Azure AI Search operation '" + operation + "' failed (kind=" + kind + suffix + ").";
    }

    /** Classifies sanitized Azure AI Search failures. */
    public enum Kind {
        /** The SDK transport failed before a valid response was available. */
        TRANSPORT,

        /** The operation deadline expired. */
        TIMEOUT,

        /** The service returned a non-authentication HTTP failure. */
        SERVICE,

        /** Authentication or authorization failed. */
        AUTHENTICATION,

        /** The configured resource was not found. */
        NOT_FOUND,

        /** A successful response violated the required data contract. */
        DATA_CONTRACT,

        /** The provider was already closed. */
        CLOSED
    }
}
