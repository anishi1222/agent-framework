// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Map;
import java.util.Objects;

/**
 * Defines immutable provider-neutral embedding generation options.
 *
 * @param model optional model or deployment override
 * @param dimensions optional requested output dimension
 * @param metadata immutable provider-neutral request metadata
 */
public record EmbeddingGenerationOptions(String model, Integer dimensions, Map<String, StateValue> metadata) {
    /** Creates and validates embedding options. */
    public EmbeddingGenerationOptions {
        model = CoreValidation.optionalNonBlank(model, "model");
        if (dimensions != null && (dimensions <= 0 || dimensions > FloatEmbeddingVector.MAX_DIMENSIONS)) {
            throw new ValidationException(
                    "dimensions must be between 1 and " + FloatEmbeddingVector.MAX_DIMENSIONS + ".");
        }
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Returns empty options.
     *
     * @return shared-value equivalent empty options
     */
    public static EmbeddingGenerationOptions empty() {
        return new EmbeddingGenerationOptions(null, null, Map.of());
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable {@link EmbeddingGenerationOptions}. */
    public static final class Builder {
        private String model;

        private Integer dimensions;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /**
         * Sets the model or deployment override.
         *
         * @param model model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the requested vector dimension.
         *
         * @param dimensions positive dimension
         * @return this builder
         */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /**
         * Sets request metadata.
         *
         * @param metadata JSON-shaped metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return embedding options
         */
        public EmbeddingGenerationOptions build() {
            return new EmbeddingGenerationOptions(model, dimensions, metadata);
        }
    }
}
