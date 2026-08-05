// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Classifies stable serialization failure categories without exposing the JSON implementation.
 */
public enum SerializationError {
    /** JSON contains the same object key more than once. */
    DUPLICATE_KEY,
    /** Input exceeds the configured UTF-8 document limit. */
    DOCUMENT_BYTES,
    /** Input exceeds the configured nesting depth. */
    NESTING_DEPTH,
    /** A decoded string exceeds the configured length. */
    STRING_LENGTH,
    /** A number token exceeds the configured length. */
    NUMERIC_TOKEN_LENGTH,
    /** An array or object exceeds the configured entry count. */
    COLLECTION_ENTRIES,
    /** JSON contains a non-finite number. */
    NON_FINITE_NUMBER,
    /** The envelope kind does not match the selected reader. */
    WRONG_DOCUMENT_KIND,
    /** The document payload version is unsupported. */
    UNSUPPORTED_PAYLOAD_VERSION,
    /** JSON has trailing tokens after the document. */
    TRAILING_CONTENT,
    /** JSON or a required schema value is malformed. */
    MALFORMED_DOCUMENT,
    /** A serialized value references an unregistered codec type identifier. */
    UNKNOWN_TYPE_ID,
    /** A codec type identifier was registered more than once. */
    DUPLICATE_CODEC,
    /** A codec version is unsupported or cannot be migrated. */
    CODEC_MIGRATION
}
