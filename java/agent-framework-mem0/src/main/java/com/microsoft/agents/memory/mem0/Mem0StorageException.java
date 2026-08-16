// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.AgentExecutionException;
import java.time.Duration;

/**
 * Reports a sanitized Mem0 transport, service, authentication, or response-contract failure.
 */
public final class Mem0StorageException extends AgentExecutionException {
    private static final long serialVersionUID = 1L;

    private final Kind kind;

    private final String operation;

    private final Integer statusCode;

    private final String requestId;

    private final Duration retryAfter;

    Mem0StorageException(Kind kind, String operation, Integer statusCode, String requestId, Duration retryAfter) {
        super(message(kind, operation, statusCode));
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the failure category.
     *
     * @return failure kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the stable operation name without request data.
     *
     * @return operation name
     */
    public String operation() {
        return operation;
    }

    /**
     * Returns the HTTP status when available.
     *
     * @return HTTP status or {@code null}
     */
    public Integer statusCode() {
        return statusCode;
    }

    /**
     * Returns a sanitized bounded service request identifier when available.
     *
     * @return request identifier or {@code null}
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns the bounded retry-after duration when supplied by the service.
     *
     * @return retry-after duration or {@code null}
     */
    public Duration retryAfter() {
        return retryAfter;
    }

    boolean continuable() {
        if (kind == Kind.TRANSPORT || kind == Kind.TIMEOUT || kind == Kind.CONCURRENCY_LIMIT) {
            return true;
        }
        return kind == Kind.SERVICE
                && statusCode != null
                && (statusCode == 408 || statusCode == 429 || statusCode >= 500 && statusCode < 600);
    }

    private static String message(Kind kind, String operation, Integer statusCode) {
        String suffix = statusCode == null ? "" : ", status=" + statusCode;
        return "Mem0 operation '" + operation + "' failed (kind=" + kind + suffix + ").";
    }

    /**
     * Classifies sanitized Mem0 failures.
     */
    public enum Kind {
        /** The JDK transport failed before a valid response was available. */
        TRANSPORT,

        /** A request or total operation deadline expired. */
        TIMEOUT,

        /** The service returned a non-authentication HTTP failure. */
        SERVICE,

        /** The service rejected authentication or authorization. */
        AUTHENTICATION,

        /** A successful HTTP response violated the documented data contract. */
        DATA_CONTRACT,

        /** An asynchronous event reported mixed or partial results. */
        PARTIAL_FAILURE,

        /** The configured concurrency limit was exhausted. */
        CONCURRENCY_LIMIT,

        /** The provider or its client was already closed. */
        CLOSED
    }
}
