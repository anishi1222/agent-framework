// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

/**
 * Reports a sanitized Valkey storage, transport, timeout, conflict, or data failure.
 *
 * <p>Messages never include keys, identifiers, credentials, scripts, or stored message content.
 */
public class ValkeyStorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Classifies a Valkey failure without exposing a GLIDE exception type. */
    public enum Kind {
        /** Authentication or authorization failed. */
        AUTHENTICATION,
        /** An append operation identifier was already bound to another payload digest. */
        CONFLICT,
        /** The server rejected or could not complete a command. */
        SERVICE,
        /** The adapter operation exceeded its configured deadline. */
        TIMEOUT,
        /** Transport failed before a valid server response. */
        TRANSPORT,
        /** Stored or encoded data is malformed, oversized, or uses an unsupported schema. */
        INCOMPATIBLE_DATA,
        /** The provider or owned client has already been closed. */
        CLOSED
    }

    private final Kind kind;

    /**
     * Creates a sanitized storage exception.
     *
     * @param message sanitized description
     * @param cause internal cause
     * @param kind stable failure category
     */
    public ValkeyStorageException(String message, Throwable cause, Kind kind) {
        super(ValkeyValidation.requireNonBlank(message, "message"), cause);
        this.kind = ValkeyValidation.requireNonNull(kind, "kind");
    }

    /**
     * Returns the stable failure category.
     *
     * @return failure kind
     */
    public Kind kind() {
        return kind;
    }
}
