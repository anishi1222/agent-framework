// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

/** Configures bounded autonomous-loop behavior. */
public final class LoopAgentOptions {
    /** Default hard iteration cap. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** Default next-iteration user message. */
    public static final String DEFAULT_NEXT_MESSAGE = "Continue working on the task. If it is complete, say so.";

    private final int maxIterations;

    private final boolean freshContextPerIteration;

    private final boolean returnFinalOnly;

    private final String defaultNextMessage;

    private final String progressAuthorName;

    private LoopAgentOptions(Builder builder) {
        if (builder.maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than zero.");
        }
        maxIterations = builder.maxIterations;
        freshContextPerIteration = builder.freshContextPerIteration;
        returnFinalOnly = builder.returnFinalOnly;
        defaultNextMessage = requireNonBlank(builder.defaultNextMessage, "defaultNextMessage");
        progressAuthorName = requireNonBlank(builder.progressAuthorName, "progressAuthorName");
    }

    /** Returns default loop options. */
    public static LoopAgentOptions defaults() {
        return builder().build();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the hard iteration cap. */
    public int maxIterations() {
        return maxIterations;
    }

    /** Returns whether each reinvocation restores the original session snapshot. */
    public boolean freshContextPerIteration() {
        return freshContextPerIteration;
    }

    /** Returns whether finite execution returns only the final response. */
    public boolean returnFinalOnly() {
        return returnFinalOnly;
    }

    /** Returns the default continuation nudge. */
    public String defaultNextMessage() {
        return defaultNextMessage;
    }

    /** Returns the author name used for synthesized progress messages. */
    public String progressAuthorName() {
        return progressAuthorName;
    }

    /** Builds immutable loop options. */
    public static final class Builder {
        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        private boolean freshContextPerIteration;

        private boolean returnFinalOnly;

        private String defaultNextMessage = DEFAULT_NEXT_MESSAGE;

        private String progressAuthorName = "harness";

        private Builder() {}

        /** Sets the hard iteration cap. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Restores the initial session snapshot before every reinvocation. */
        public Builder freshContextPerIteration(boolean freshContextPerIteration) {
            this.freshContextPerIteration = freshContextPerIteration;
            return this;
        }

        /** Returns only the final iteration's response. */
        public Builder returnFinalOnly(boolean returnFinalOnly) {
            this.returnFinalOnly = returnFinalOnly;
            return this;
        }

        /** Sets the default continuation nudge. */
        public Builder defaultNextMessage(String defaultNextMessage) {
            this.defaultNextMessage = defaultNextMessage;
            return this;
        }

        /** Sets the synthesized-message author name. */
        public Builder progressAuthorName(String progressAuthorName) {
            this.progressAuthorName = progressAuthorName;
            return this;
        }

        /** Creates immutable options. */
        public LoopAgentOptions build() {
            return new LoopAgentOptions(this);
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
