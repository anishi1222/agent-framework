// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

/**
 * Names the portable resource limits that session and checkpoint JSON readers must enforce.
 *
 * @param maxDocumentBytes maximum UTF-8 document size in bytes
 * @param maxNestingDepth maximum JSON nesting depth
 * @param maxStringLength maximum decoded string length
 * @param maxNumericTokenLength maximum numeric token length
 * @param maxCollectionEntries maximum entries in one array or object
 */
public record SerializationLimits(
        long maxDocumentBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxNumericTokenLength,
        int maxCollectionEntries) {
    /** Validates that every configured limit is positive. */
    public SerializationLimits {
        if (maxDocumentBytes <= 0
                || maxNestingDepth <= 0
                || maxStringLength <= 0
                || maxNumericTokenLength <= 0
                || maxCollectionEntries <= 0) {
            throw new IllegalArgumentException("Serialization limits must all be positive.");
        }
    }
}
