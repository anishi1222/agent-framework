// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Arrays;

/** Classifies the portable parser or envelope rule that a raw case must reject. */
public enum SerializationRejectionReason {
    /** Duplicate JSON object key. */
    DUPLICATE_KEY("duplicate-key"),
    /** UTF-8 document exceeds its byte limit. */
    DOCUMENT_BYTES("document-bytes"),
    /** JSON nesting exceeds its depth limit. */
    NESTING_DEPTH("nesting-depth"),
    /** Decoded JSON string exceeds its length limit. */
    STRING_LENGTH("string-length"),
    /** Numeric token exceeds its length limit. */
    NUMERIC_TOKEN_LENGTH("numeric-token-length"),
    /** One JSON array or object exceeds its entry limit. */
    COLLECTION_ENTRIES("collection-entries"),
    /** JSON contains a non-finite numeric token. */
    NON_FINITE_NUMBER("non-finite-number"),
    /** Envelope document kind does not match the target reader. */
    WRONG_DOCUMENT_KIND("wrong-document-kind"),
    /** Envelope payload version is unsupported. */
    UNSUPPORTED_PAYLOAD_VERSION("unsupported-payload-version");

    private final String wireName;

    SerializationRejectionReason(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the rejection-manifest wire name.
     *
     * @return wire name
     */
    public String wireName() {
        return wireName;
    }

    static SerializationRejectionReason fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(reason -> reason.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new ConformanceValidationException(
                        "Unknown serialization rejection reason '" + wireName + "'."));
    }
}
