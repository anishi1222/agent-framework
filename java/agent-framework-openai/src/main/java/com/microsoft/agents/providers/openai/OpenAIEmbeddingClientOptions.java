// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Defines immutable OpenAI embedding client configuration. */
public final class OpenAIEmbeddingClientOptions {
    /** Maximum number of values accepted in one OpenAI embeddings request. */
    public static final int MAX_BATCH_SIZE = 2048;

    private final OpenAISecret apiKey;

    private final String model;

    private final URI baseUrl;

    private final String organization;

    private final String project;

    private final Duration timeout;

    private final int maxRetries;

    private final int maxBatchSize;

    private OpenAIEmbeddingClientOptions(Builder builder) {
        apiKey = builder.apiKey;
        model = requireNonBlank(builder.model, "model");
        baseUrl = validateBaseUrl(builder.baseUrl);
        organization = optionalNonBlank(builder.organization, "organization");
        project = optionalNonBlank(builder.project, "project");
        timeout = validateTimeout(builder.timeout);
        if (builder.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative.");
        }
        maxRetries = builder.maxRetries;
        if (builder.maxBatchSize <= 0 || builder.maxBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("maxBatchSize must be between 1 and " + MAX_BATCH_SIZE + ".");
        }
        maxBatchSize = builder.maxBatchSize;
    }

    /**
     * Creates a client-options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether an explicit API key is configured.
     *
     * @return {@code true} when a key is present
     */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    /**
     * Returns the default embedding model.
     *
     * @return model identifier
     */
    public String model() {
        return model;
    }

    /**
     * Returns the optional custom base URL.
     *
     * @return base URL
     */
    public Optional<URI> baseUrl() {
        return Optional.ofNullable(baseUrl);
    }

    /**
     * Returns the optional organization.
     *
     * @return organization identifier
     */
    public Optional<String> organization() {
        return Optional.ofNullable(organization);
    }

    /**
     * Returns the optional project.
     *
     * @return project identifier
     */
    public Optional<String> project() {
        return Optional.ofNullable(project);
    }

    /**
     * Returns the optional request timeout.
     *
     * @return timeout
     */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /**
     * Returns the SDK retry limit.
     *
     * @return non-negative retry limit
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the automatic request batch size.
     *
     * @return positive batch size
     */
    public int maxBatchSize() {
        return maxBatchSize;
    }

    OpenAISecret apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "OpenAIEmbeddingClientOptions{apiKey="
                + (apiKey == null ? "<absent>" : "[REDACTED]")
                + ", model='"
                + model
                + "', baseUrl="
                + baseUrl
                + ", organization="
                + organization
                + ", project="
                + project
                + ", timeout="
                + timeout
                + ", maxRetries="
                + maxRetries
                + ", maxBatchSize="
                + maxBatchSize
                + '}';
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        return value == null ? null : requireNonBlank(value, name);
    }

    private static URI validateBaseUrl(URI value) {
        if (value != null && (!value.isAbsolute() || value.getScheme() == null)) {
            throw new IllegalArgumentException("baseUrl must be absolute.");
        }
        return value;
    }

    private static Duration validateTimeout(Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return value;
    }

    /** Builds immutable {@link OpenAIEmbeddingClientOptions}. */
    public static final class Builder {
        private OpenAISecret apiKey;

        private String model;

        private URI baseUrl;

        private String organization;

        private String project;

        private Duration timeout;

        private int maxRetries = 2;

        private int maxBatchSize = MAX_BATCH_SIZE;

        private Builder() {}

        /** Sets an API key. */
        public Builder apiKey(String apiKey) {
            this.apiKey = OpenAISecret.of(apiKey);
            return this;
        }

        /** Sets an already wrapped API key. */
        public Builder apiKey(OpenAISecret apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /** Sets the required default model. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets a custom OpenAI-compatible base URL. */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Sets a custom OpenAI-compatible base URL. */
        public Builder baseUrl(String baseUrl) {
            return baseUrl(URI.create(Objects.requireNonNull(baseUrl, "baseUrl")));
        }

        /** Sets an OpenAI organization. */
        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        /** Sets an OpenAI project. */
        public Builder project(String project) {
            this.project = project;
            return this;
        }

        /** Sets the timeout for one SDK HTTP attempt. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sets the SDK retry limit. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** Sets the maximum number of values sent in one request. */
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /** Creates immutable client options. */
        public OpenAIEmbeddingClientOptions build() {
            return new OpenAIEmbeddingClientOptions(this);
        }
    }
}
