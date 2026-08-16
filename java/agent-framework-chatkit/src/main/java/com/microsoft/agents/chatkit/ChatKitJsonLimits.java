// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

/**
 * Defines strict resource limits for ChatKit JSON documents.
 *
 * @param maxDocumentBytes maximum UTF-8 document size
 * @param maxStringCharacters maximum characters in a string or field name
 * @param maxCollectionSize maximum entries in any object or array
 * @param maxNestingDepth maximum JSON nesting depth
 * @param maxNumberCharacters maximum characters in a numeric token
 */
public record ChatKitJsonLimits(
        int maxDocumentBytes,
        int maxStringCharacters,
        int maxCollectionSize,
        int maxNestingDepth,
        int maxNumberCharacters) {

    /** Validates and creates JSON limits. */
    public ChatKitJsonLimits {
        requirePositive(maxDocumentBytes, "maxDocumentBytes");
        requirePositive(maxStringCharacters, "maxStringCharacters");
        requirePositive(maxCollectionSize, "maxCollectionSize");
        requirePositive(maxNestingDepth, "maxNestingDepth");
        requirePositive(maxNumberCharacters, "maxNumberCharacters");
    }

    /** Returns conservative defaults for self-hosted ChatKit traffic. */
    public static ChatKitJsonLimits defaults() {
        return new ChatKitJsonLimits(1_048_576, 65_536, 1_024, 32, 128);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }
}
