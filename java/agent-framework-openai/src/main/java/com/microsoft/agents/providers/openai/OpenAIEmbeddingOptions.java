// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import com.microsoft.agents.core.EmbeddingGenerationOptions;
import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Defines immutable per-request OpenAI embedding options.
 *
 * @param model optional model override
 * @param dimensions optional requested output dimension
 * @param encodingFormat optional response encoding, defaulting to {@link
 *     OpenAIEmbeddingEncodingFormat#FLOAT}
 * @param user optional stable end-user identifier
 * @param metadata immutable request metadata
 */
public record OpenAIEmbeddingOptions(
        String model,
        Integer dimensions,
        OpenAIEmbeddingEncodingFormat encodingFormat,
        String user,
        Map<String, StateValue> metadata) {
    /** Creates and validates request options. */
    public OpenAIEmbeddingOptions {
        EmbeddingGenerationOptions common = new EmbeddingGenerationOptions(model, dimensions, metadata);
        model = common.model();
        dimensions = common.dimensions();
        metadata = common.metadata();
        if (user != null && user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank.");
        }
    }

    /**
     * Returns empty request options.
     *
     * @return empty options
     */
    public static OpenAIEmbeddingOptions empty() {
        return new OpenAIEmbeddingOptions(null, null, null, null, Map.of());
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable {@link OpenAIEmbeddingOptions}. */
    public static final class Builder {
        private String model;

        private Integer dimensions;

        private OpenAIEmbeddingEncodingFormat encodingFormat;

        private String user;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /** Sets the model override. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the requested vector dimension. */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /** Sets the provider response encoding. */
        public Builder encodingFormat(OpenAIEmbeddingEncodingFormat encodingFormat) {
            this.encodingFormat = Objects.requireNonNull(encodingFormat, "encodingFormat");
            return this;
        }

        /** Sets the stable end-user identifier. */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /** Sets request metadata. */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /** Creates immutable request options. */
        public OpenAIEmbeddingOptions build() {
            return new OpenAIEmbeddingOptions(model, dimensions, encodingFormat, user, metadata);
        }
    }
}
