// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.Optional;

/** Defines standard JSON-RPC and A2A v1 protocol error codes. */
public enum A2AErrorCode {
    /** Malformed JSON. */
    PARSE_ERROR(-32700),
    /** Invalid JSON-RPC request. */
    INVALID_REQUEST(-32600),
    /** Unknown method. */
    METHOD_NOT_FOUND(-32601),
    /** Invalid operation parameters. */
    INVALID_PARAMS(-32602),
    /** Unexpected server failure. */
    INTERNAL_ERROR(-32603),
    /** Task was not found or not visible to the principal. */
    TASK_NOT_FOUND(-32001),
    /** Task cannot be canceled in its current state. */
    TASK_NOT_CANCELABLE(-32002),
    /** Push configuration is unsupported. */
    PUSH_NOTIFICATION_NOT_SUPPORTED(-32003),
    /** Operation is unsupported. */
    UNSUPPORTED_OPERATION(-32004),
    /** Content type is unsupported. */
    CONTENT_TYPE_NOT_SUPPORTED(-32005),
    /** Agent emitted an invalid response. */
    INVALID_AGENT_RESPONSE(-32006),
    /** Extended card was not configured. */
    EXTENDED_AGENT_CARD_NOT_CONFIGURED(-32007),
    /** A required extension is unsupported. */
    EXTENSION_SUPPORT_REQUIRED(-32008),
    /** Protocol version is unsupported. */
    VERSION_NOT_SUPPORTED(-32009);

    private final int code;

    A2AErrorCode(int code) {
        this.code = code;
    }

    /**
     * Returns the wire error code.
     *
     * @return integer code
     */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code.
     *
     * @param value wire code
     * @return matching standard code, or empty for future and application-defined codes
     */
    public static Optional<A2AErrorCode> fromCode(int value) {
        for (A2AErrorCode candidate : values()) {
            if (candidate.code == value) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
