// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

/**
 * Defines mandatory resource limits for a state JSON reader or writer.
 *
 * @param maxDocumentBytes maximum UTF-8 document size
 * @param maxNestingDepth maximum JSON nesting depth
 * @param maxStringLength maximum decoded string length
 * @param maxNumericTokenLength maximum numeric token length
 * @param maxCollectionEntries maximum entries in any one object or array
 */
public record SerializationLimits(
        long maxDocumentBytes,
        int maxNestingDepth,
        int maxStringLength,
        int maxNumericTokenLength,
        int maxCollectionEntries) {
    /** Creates validated positive limits. */
    public SerializationLimits {
        if (maxDocumentBytes <= 0
                || maxNestingDepth <= 0
                || maxStringLength <= 0
                || maxNumericTokenLength <= 0
                || maxCollectionEntries <= 0) {
            throw new ValidationException("Serialization limits must all be greater than zero.");
        }
    }

    /**
     * Returns conservative defaults suitable for framework state.
     *
     * @return default limits
     */
    public static SerializationLimits defaults() {
        return new SerializationLimits(16 * 1024 * 1024L, 128, 1_000_000, 1_000, 100_000);
    }
}
