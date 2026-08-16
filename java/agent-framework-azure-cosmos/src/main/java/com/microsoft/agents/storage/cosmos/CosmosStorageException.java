// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

/**
 * Reports a sanitized Cosmos storage, transport, timeout, or schema failure.
 *
 * <p>Messages and diagnostics never include document content, query parameter values, or
 * credentials.
 */
public class CosmosStorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Classifies a storage failure without exposing an SDK exception type. */
    public enum Kind {
        /** Authentication or authorization failed. */
        AUTHENTICATION,
        /** A configured resource or stored item was absent. */
        NOT_FOUND,
        /** The Cosmos service rejected a request. */
        SERVICE,
        /** The adapter operation exceeded its configured deadline. */
        TIMEOUT,
        /** Transport failed before a valid service response. */
        TRANSPORT,
        /** Existing resource policy or stored schema is incompatible. */
        INCOMPATIBLE_RESOURCE,
        /** The adapter has already been closed. */
        CLOSED
    }

    private final Kind kind;

    private final CosmosOperationDiagnostics diagnostics;

    /**
     * Creates a sanitized storage exception.
     *
     * @param message sanitized description
     * @param cause internal cause
     * @param kind failure category
     * @param diagnostics sanitized diagnostics
     */
    public CosmosStorageException(String message, Throwable cause, Kind kind, CosmosOperationDiagnostics diagnostics) {
        super(CosmosValidation.requireNonBlank(message, "message"), cause);
        this.kind = CosmosValidation.requireNonNull(kind, "kind");
        this.diagnostics = diagnostics;
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
     * Returns sanitized request diagnostics.
     *
     * @return diagnostics, or {@code null}
     */
    public CosmosOperationDiagnostics diagnostics() {
        return diagnostics;
    }
}
