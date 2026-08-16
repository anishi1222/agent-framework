// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

/** Classifies bounded AG-UI protocol, validation, and transport failures. */
public enum AGUIErrorCode {
    /** JSON text or an SSE frame is malformed. */
    MALFORMED_INPUT,
    /** A configured byte, depth, string, number, collection, or event bound was exceeded. */
    LIMIT_EXCEEDED,
    /** The wire discriminator is not in the pinned standard event set. */
    UNKNOWN_EVENT,
    /** A wire model violates its field contract. */
    INVALID_MODEL,
    /** Events violate the AG-UI lifecycle or target ordering rules. */
    INVALID_SEQUENCE,
    /** A JSON Patch document, pointer, or operation is invalid. */
    INVALID_PATCH,
    /** Endpoint or request security policy rejected the operation. */
    SECURITY,
    /** HTTP or SSE transport failed. */
    TRANSPORT,
    /** The caller cancelled the operation. */
    CANCELLED,
    /** A configured timeout elapsed. */
    TIMEOUT,
    /** A bounded publisher or consumer could not accept more data. */
    OVERFLOW
}
