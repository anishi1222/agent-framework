// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.microsoft.agents.core.EmbeddingGenerationOptions;
import com.microsoft.agents.core.StateValue;
import java.util.Map;
import java.util.Objects;

/**
 * Defines immutable per-request Azure OpenAI embedding options.
 *
 * @param model optional deployment override
 * @param dimensions optional requested output dimension
 * @param user optional stable end-user identifier
 * @param inputType optional Azure embedding input type
 * @param metadata immutable request metadata
 */
public record AzureOpenAIEmbeddingOptions(
        String model, Integer dimensions, String user, String inputType, Map<String, StateValue> metadata) {
    /** Creates and validates request options. */
    public AzureOpenAIEmbeddingOptions {
        EmbeddingGenerationOptions common = new EmbeddingGenerationOptions(model, dimensions, metadata);
        model = common.model();
        dimensions = common.dimensions();
        metadata = common.metadata();
        user = optionalNonBlank(user, "user");
        inputType = optionalNonBlank(inputType, "inputType");
    }

    /**
     * Returns empty request options.
     *
     * @return empty options
     */
    public static AzureOpenAIEmbeddingOptions empty() {
        return new AzureOpenAIEmbeddingOptions(null, null, null, null, Map.of());
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static String optionalNonBlank(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    /** Builds immutable {@link AzureOpenAIEmbeddingOptions}. */
    public static final class Builder {
        private String model;

        private Integer dimensions;

        private String user;

        private String inputType;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /** Sets the deployment override. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the requested vector dimension. */
        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        /** Sets the stable end-user identifier. */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /** Sets the Azure embedding input type. */
        public Builder inputType(String inputType) {
            this.inputType = inputType;
            return this;
        }

        /** Sets request metadata. */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /** Creates immutable request options. */
        public AzureOpenAIEmbeddingOptions build() {
            return new AzureOpenAIEmbeddingOptions(model, dimensions, user, inputType, metadata);
        }
    }
}
