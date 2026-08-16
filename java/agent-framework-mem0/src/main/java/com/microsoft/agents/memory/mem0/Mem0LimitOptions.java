// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.ValidationException;

/**
 * Defines finite Mem0 request, response, JSON, concurrency, query, and context limits.
 */
public final class Mem0LimitOptions {
    private static final Mem0LimitOptions DEFAULTS = builder().build();

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxNumericTokenLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private final int maxQueryCharacters;

    private final int topK;

    private final int maxSnippetCharacters;

    private final int contextCharacterBudget;

    private final int maxStoredMessages;

    private final int maxMessageCharacters;

    private final int maxMemoryIdCharacters;

    private Mem0LimitOptions(Builder builder) {
        maxRequestBytes = bounded(builder.maxRequestBytes, 1, 64 * 1024 * 1024, "maxRequestBytes");
        maxResponseBytes = bounded(builder.maxResponseBytes, 1, 64 * 1024 * 1024, "maxResponseBytes");
        maxNestingDepth = bounded(builder.maxNestingDepth, 1, 256, "maxNestingDepth");
        maxStringLength = bounded(builder.maxStringLength, 1, 16 * 1024 * 1024, "maxStringLength");
        maxNumericTokenLength = bounded(builder.maxNumericTokenLength, 1, 10_000, "maxNumericTokenLength");
        maxCollectionEntries = bounded(builder.maxCollectionEntries, 1, 1_000_000, "maxCollectionEntries");
        maxConcurrentRequests = bounded(builder.maxConcurrentRequests, 1, 10_000, "maxConcurrentRequests");
        maxQueryCharacters = bounded(builder.maxQueryCharacters, 1, 1_000_000, "maxQueryCharacters");
        topK = bounded(builder.topK, 1, 1000, "topK");
        maxSnippetCharacters = bounded(builder.maxSnippetCharacters, 1, maxStringLength, "maxSnippetCharacters");
        contextCharacterBudget =
                bounded(builder.contextCharacterBudget, 256, 16 * 1024 * 1024, "contextCharacterBudget");
        maxStoredMessages = bounded(builder.maxStoredMessages, 1, 10_000, "maxStoredMessages");
        maxMessageCharacters = bounded(builder.maxMessageCharacters, 1, maxStringLength, "maxMessageCharacters");
        maxMemoryIdCharacters = bounded(builder.maxMemoryIdCharacters, 1, maxStringLength, "maxMemoryIdCharacters");
    }

    /**
     * Returns conservative default limits.
     *
     * @return shared immutable defaults
     */
    public static Mem0LimitOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a limits builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the maximum encoded request bytes. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** Returns the maximum response bytes. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the maximum JSON nesting depth. */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /** Returns the maximum JSON string or member-name length. */
    public int maxStringLength() {
        return maxStringLength;
    }

    /** Returns the maximum JSON numeric-token length. */
    public int maxNumericTokenLength() {
        return maxNumericTokenLength;
    }

    /** Returns the maximum members per JSON object or elements per JSON array. */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /** Returns the maximum concurrent HTTP requests. */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /** Returns the maximum caller-query characters sent to Mem0. */
    public int maxQueryCharacters() {
        return maxQueryCharacters;
    }

    /** Returns the bounded V3 search {@code top_k}. */
    public int topK() {
        return topK;
    }

    /** Returns the maximum characters injected from one memory. */
    public int maxSnippetCharacters() {
        return maxSnippetCharacters;
    }

    /** Returns the total retrieved-context character budget. */
    public int contextCharacterBudget() {
        return contextCharacterBudget;
    }

    /** Returns the maximum messages in one V3 add request. */
    public int maxStoredMessages() {
        return maxStoredMessages;
    }

    /** Returns the maximum characters in one stored message. */
    public int maxMessageCharacters() {
        return maxMessageCharacters;
    }

    /** Returns the maximum accepted memory-identifier characters. */
    public int maxMemoryIdCharacters() {
        return maxMemoryIdCharacters;
    }

    @Override
    public String toString() {
        return "Mem0LimitOptions{maxRequestBytes="
                + maxRequestBytes
                + ", maxResponseBytes="
                + maxResponseBytes
                + ", maxNestingDepth="
                + maxNestingDepth
                + ", maxStringLength="
                + maxStringLength
                + ", maxNumericTokenLength="
                + maxNumericTokenLength
                + ", maxCollectionEntries="
                + maxCollectionEntries
                + ", maxConcurrentRequests="
                + maxConcurrentRequests
                + ", maxQueryCharacters="
                + maxQueryCharacters
                + ", topK="
                + topK
                + ", maxSnippetCharacters="
                + maxSnippetCharacters
                + ", contextCharacterBudget="
                + contextCharacterBudget
                + ", maxStoredMessages="
                + maxStoredMessages
                + ", maxMessageCharacters="
                + maxMessageCharacters
                + ", maxMemoryIdCharacters="
                + maxMemoryIdCharacters
                + '}';
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new ValidationException(name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    /** Builds immutable {@link Mem0LimitOptions}. */
    public static final class Builder {
        private int maxRequestBytes = 2 * 1024 * 1024;

        private int maxResponseBytes = 8 * 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 1024 * 1024;

        private int maxNumericTokenLength = 1000;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 32;

        private int maxQueryCharacters = 8192;

        private int topK = 10;

        private int maxSnippetCharacters = 2000;

        private int contextCharacterBudget = 8000;

        private int maxStoredMessages = 128;

        private int maxMessageCharacters = 64 * 1024;

        private int maxMemoryIdCharacters = 512;

        private Builder() {}

        /** Sets the maximum encoded request bytes. */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        /** Sets the maximum response bytes. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets the maximum JSON nesting depth. */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /** Sets the maximum JSON string and member-name length. */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /** Sets the maximum JSON numeric-token length. */
        public Builder maxNumericTokenLength(int value) {
            maxNumericTokenLength = value;
            return this;
        }

        /** Sets the maximum members per object or elements per array. */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /** Sets the maximum concurrent HTTP requests. */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /** Sets the maximum search-query characters. */
        public Builder maxQueryCharacters(int value) {
            maxQueryCharacters = value;
            return this;
        }

        /** Sets the V3 search {@code top_k}. */
        public Builder topK(int value) {
            topK = value;
            return this;
        }

        /** Sets the maximum characters injected from one memory. */
        public Builder maxSnippetCharacters(int value) {
            maxSnippetCharacters = value;
            return this;
        }

        /** Sets the total retrieved-context character budget. */
        public Builder contextCharacterBudget(int value) {
            contextCharacterBudget = value;
            return this;
        }

        /** Sets the maximum messages in one V3 add request. */
        public Builder maxStoredMessages(int value) {
            maxStoredMessages = value;
            return this;
        }

        /** Sets the maximum characters in one stored message. */
        public Builder maxMessageCharacters(int value) {
            maxMessageCharacters = value;
            return this;
        }

        /** Sets the maximum accepted memory-identifier characters. */
        public Builder maxMemoryIdCharacters(int value) {
            maxMemoryIdCharacters = value;
            return this;
        }

        /**
         * Creates immutable limits.
         *
         * @return limit options
         */
        public Mem0LimitOptions build() {
            return new Mem0LimitOptions(this);
        }
    }
}
