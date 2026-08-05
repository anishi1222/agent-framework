// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;

/**
 * Maps the constraint messages exposed by the pinned Jackson version to framework-owned errors.
 */
final class JacksonStreamConstraintMapper {
    static final String MAPPED_JACKSON_VERSION = "2.22.1";

    private JacksonStreamConstraintMapper() {}

    static SerializationException map(StreamConstraintsException exception) {
        String message = exception.getOriginalMessage();
        SerializationError error = classify(message == null ? "" : message);
        String detail = error == SerializationError.MALFORMED_DOCUMENT
                ? "Unrecognized Jackson "
                        + MAPPED_JACKSON_VERSION
                        + " stream constraint: "
                        + (message == null ? "<no message>" : message)
                : "State JSON violates the " + portableLimitName(error) + " constraint.";
        return new SerializationException(error, detail, exception);
    }

    private static SerializationError classify(String message) {
        if (message.startsWith("Document nesting depth (")) {
            return SerializationError.NESTING_DEPTH;
        }
        if (message.startsWith("String value length (") || message.startsWith("Name length (")) {
            return SerializationError.STRING_LENGTH;
        }
        if (message.startsWith("Number value length (")) {
            return SerializationError.NUMERIC_TOKEN_LENGTH;
        }
        if (message.startsWith("Document length (")) {
            return SerializationError.DOCUMENT_BYTES;
        }
        return SerializationError.MALFORMED_DOCUMENT;
    }

    private static String portableLimitName(SerializationError error) {
        return switch (error) {
            case DOCUMENT_BYTES -> "maxDocumentBytes";
            case NESTING_DEPTH -> "maxNestingDepth";
            case STRING_LENGTH -> "maxStringLength";
            case NUMERIC_TOKEN_LENGTH -> "maxNumericTokenLength";
            default -> throw new IllegalArgumentException("Not a stream-constraint error: " + error);
        };
    }
}
