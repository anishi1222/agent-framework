// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Defines immutable provider-neutral limits and metadata for one agent run.
 *
 * @param maxIterations optional positive orchestration iteration limit
 * @param maxFunctionCalls optional positive function-call limit
 * @param metadata immutable per-run metadata
 */
public record RunOptions(Integer maxIterations, Integer maxFunctionCalls, Map<String, StateValue> metadata) {
    /** Creates validated run options. */
    public RunOptions {
        requirePositive(maxIterations, "maxIterations");
        requirePositive(maxFunctionCalls, "maxFunctionCalls");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Returns empty options.
     *
     * @return options with no explicit limits or metadata
     */
    public static RunOptions empty() {
        return new RunOptions(null, null, Map.of());
    }

    /**
     * Creates a builder.
     *
     * @return run-options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new ValidationException(name + " must be greater than zero when present.");
        }
    }

    /** Builds immutable {@link RunOptions}. */
    public static final class Builder {
        private Integer maxIterations;

        private Integer maxFunctionCalls;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /**
         * Sets the orchestration iteration limit.
         *
         * @param maxIterations positive limit
         * @return this builder
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the function-call limit.
         *
         * @param maxFunctionCalls positive limit
         * @return this builder
         */
        public Builder maxFunctionCalls(int maxFunctionCalls) {
            this.maxFunctionCalls = maxFunctionCalls;
            return this;
        }

        /**
         * Sets immutable per-run metadata.
         *
         * @param metadata metadata values
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates the immutable options.
         *
         * @return run options
         */
        public RunOptions build() {
            return new RunOptions(maxIterations, maxFunctionCalls, metadata);
        }
    }
}
