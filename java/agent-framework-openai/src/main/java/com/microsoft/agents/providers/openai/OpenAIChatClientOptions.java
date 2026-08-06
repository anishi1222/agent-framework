// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines immutable configuration for {@link OpenAIChatClient}.
 *
 * <p>For this provider, a request {@code ChatOptions.conversationId} beginning with {@code conv_}
 * is sent as a Responses API conversation ID. Any other non-blank value is treated as a prior
 * response ID and sent as {@code previous_response_id}. The two continuation mechanisms are
 * mutually exclusive.
 */
public final class OpenAIChatClientOptions {
    /** Default maximum number of provider updates retained without downstream demand. */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private final OpenAISecret apiKey;

    private final String model;

    private final URI baseUrl;

    private final String organization;

    private final String project;

    private final Duration timeout;

    private final int maxRetries;

    private final int maxBufferedUpdates;

    private final OpenAIResponseOptions responseOptions;

    private OpenAIChatClientOptions(Builder builder) {
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
        if (builder.maxBufferedUpdates <= 0) {
            throw new IllegalArgumentException("maxBufferedUpdates must be greater than zero.");
        }
        maxBufferedUpdates = builder.maxBufferedUpdates;
        responseOptions = Objects.requireNonNull(builder.responseOptions, "responseOptions");
    }

    /**
     * Creates a configuration builder.
     *
     * @return configuration builder
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
     * Returns the configured model.
     *
     * @return model identifier
     */
    public String model() {
        return model;
    }

    /**
     * Returns the optional base URL.
     *
     * @return base URL
     */
    public Optional<URI> baseUrl() {
        return Optional.ofNullable(baseUrl);
    }

    /**
     * Returns the optional organization.
     *
     * @return organization
     */
    public Optional<String> organization() {
        return Optional.ofNullable(organization);
    }

    /**
     * Returns the optional project.
     *
     * @return project
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
     * @return retry limit
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the bounded streaming-update capacity.
     *
     * @return positive update capacity
     */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    /**
     * Returns default provider-specific response options.
     *
     * @return response options
     */
    public OpenAIResponseOptions responseOptions() {
        return responseOptions;
    }

    OpenAISecret apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "OpenAIChatClientOptions{apiKey="
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
                + ", maxBufferedUpdates="
                + maxBufferedUpdates
                + ", responseOptions="
                + responseOptions
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

    private static URI validateBaseUrl(URI baseUrl) {
        if (baseUrl != null && (!baseUrl.isAbsolute() || baseUrl.getScheme() == null)) {
            throw new IllegalArgumentException("baseUrl must be absolute.");
        }
        return baseUrl;
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return timeout;
    }

    /** Builds immutable {@link OpenAIChatClientOptions}. */
    public static final class Builder {
        private OpenAISecret apiKey;

        private String model;

        private URI baseUrl;

        private String organization;

        private String project;

        private Duration timeout;

        private int maxRetries = 2;

        private int maxBufferedUpdates = DEFAULT_MAX_BUFFERED_UPDATES;

        private OpenAIResponseOptions responseOptions = OpenAIResponseOptions.defaults();

        private Builder() {}

        /**
         * Sets an API key.
         *
         * @param apiKey non-blank key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = OpenAISecret.of(apiKey);
            return this;
        }

        /**
         * Sets an already wrapped API key.
         *
         * @param apiKey secret key
         * @return this builder
         */
        public Builder apiKey(OpenAISecret apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            return this;
        }

        /**
         * Sets the required default model.
         *
         * @param model model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets a custom OpenAI-compatible base URL.
         *
         * @param baseUrl absolute base URL
         * @return this builder
         */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /**
         * Sets a custom OpenAI-compatible base URL.
         *
         * @param baseUrl absolute base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            return baseUrl(URI.create(Objects.requireNonNull(baseUrl, "baseUrl")));
        }

        /**
         * Sets an OpenAI organization.
         *
         * @param organization organization identifier
         * @return this builder
         */
        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        /**
         * Sets an OpenAI project.
         *
         * @param project project identifier
         * @return this builder
         */
        public Builder project(String project) {
            this.project = project;
            return this;
        }

        /**
         * Sets the timeout for one SDK HTTP attempt, excluding retries.
         *
         * @param timeout positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the SDK retry limit.
         *
         * @param maxRetries non-negative retry limit
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the maximum number of undelivered streaming updates.
         *
         * @param maxBufferedUpdates positive update capacity
         * @return this builder
         */
        public Builder maxBufferedUpdates(int maxBufferedUpdates) {
            this.maxBufferedUpdates = maxBufferedUpdates;
            return this;
        }

        /**
         * Sets default provider-specific response options.
         *
         * @param responseOptions response options
         * @return this builder
         */
        public Builder responseOptions(OpenAIResponseOptions responseOptions) {
            this.responseOptions = Objects.requireNonNull(responseOptions, "responseOptions");
            return this;
        }

        /**
         * Creates immutable client options.
         *
         * @return client options
         */
        public OpenAIChatClientOptions build() {
            return new OpenAIChatClientOptions(this);
        }
    }
}
