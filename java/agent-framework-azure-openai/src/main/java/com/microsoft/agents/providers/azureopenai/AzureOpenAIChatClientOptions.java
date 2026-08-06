// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Defines immutable Azure OpenAI Responses client configuration.
 *
 * <p>A caller-supplied {@link TokenCredential} remains caller-owned. The provider never serializes,
 * logs, or closes it. Applications that call {@link Builder#tokenCredential(TokenCredential)} must
 * declare an Azure Identity or Azure Core compile dependency because Azure credential types are an
 * optional provider API surface.
 */
public final class AzureOpenAIChatClientOptions {
    /** Latest API version supported by the pinned Azure OpenAI Responses SDK. */
    public static final String DEFAULT_API_VERSION = "2025-03-01-preview";

    /** Default total request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /** Default maximum number of provider retries. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Default maximum number of undelivered streaming updates. */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private final URI endpoint;

    private final String deployment;

    private final String apiVersion;

    private final Secret apiKey;

    private final TokenCredential tokenCredential;

    private final Duration timeout;

    private final int maxRetries;

    private final int maxBufferedUpdates;

    private AzureOpenAIChatClientOptions(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint);
        deployment = requireNonBlank(builder.deployment, "deployment");
        apiVersion = validateApiVersion(builder.apiVersion);
        apiKey = builder.apiKey;
        tokenCredential = builder.tokenCredential;
        if ((apiKey == null) == (tokenCredential == null)) {
            throw new IllegalArgumentException("Exactly one of apiKey or tokenCredential must be configured.");
        }
        timeout = validateTimeout(builder.timeout);
        if (builder.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative.");
        }
        maxRetries = builder.maxRetries;
        if (builder.maxBufferedUpdates <= 0) {
            throw new IllegalArgumentException("maxBufferedUpdates must be greater than zero.");
        }
        maxBufferedUpdates = builder.maxBufferedUpdates;
    }

    /**
     * Creates an options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the Azure OpenAI resource endpoint.
     *
     * @return validated HTTPS endpoint
     */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * Returns the Azure OpenAI deployment name.
     *
     * @return deployment name
     */
    public String deployment() {
        return deployment;
    }

    /**
     * Returns the configured service API version.
     *
     * @return API version
     */
    public String apiVersion() {
        return apiVersion;
    }

    /**
     * Returns the authentication mode without exposing credential material.
     *
     * @return authentication mode
     */
    public AzureOpenAIAuthenticationMode authenticationMode() {
        return apiKey == null ? AzureOpenAIAuthenticationMode.TOKEN_CREDENTIAL : AzureOpenAIAuthenticationMode.API_KEY;
    }

    /**
     * Returns the total request timeout.
     *
     * @return positive timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the maximum provider retry count.
     *
     * @return non-negative retry count
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the bounded streaming-update capacity.
     *
     * @return positive capacity
     */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    String apiKey() {
        return apiKey == null ? null : apiKey.reveal();
    }

    TokenCredential tokenCredential() {
        return tokenCredential;
    }

    @Override
    public String toString() {
        return "AzureOpenAIChatClientOptions{endpoint="
                + endpoint
                + ", deployment='"
                + deployment
                + "', apiVersion='"
                + apiVersion
                + "', authenticationMode="
                + authenticationMode()
                + ", credential=[REDACTED], timeout="
                + timeout
                + ", maxRetries="
                + maxRetries
                + ", maxBufferedUpdates="
                + maxBufferedUpdates
                + '}';
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null
                || value.getHost().isBlank()
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be an absolute HTTPS URI with a host and without user info, query, or fragment.");
        }
        return value;
    }

    private static String validateApiVersion(String value) {
        String version = requireNonBlank(value, "apiVersion");
        return switch (version) {
            case "2024-02-15-preview",
                    "2024-04-01-preview",
                    "2024-06-01",
                    "2024-08-01-preview",
                    "2024-09-01-preview",
                    "2024-10-01-preview",
                    "2024-10-21",
                    "2024-12-01-preview",
                    "2025-01-01-preview",
                    DEFAULT_API_VERSION -> version;
            default ->
                throw new IllegalArgumentException("apiVersion is not supported by azure-ai-openai 1.0.0-beta.16.");
        };
    }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    /** Builds immutable {@link AzureOpenAIChatClientOptions}. */
    public static final class Builder {
        private URI endpoint;

        private String deployment;

        private String apiVersion = DEFAULT_API_VERSION;

        private Secret apiKey;

        private TokenCredential tokenCredential;

        private Duration timeout = DEFAULT_TIMEOUT;

        private int maxRetries = DEFAULT_MAX_RETRIES;

        private int maxBufferedUpdates = DEFAULT_MAX_BUFFERED_UPDATES;

        private Builder() {}

        /**
         * Sets the Azure OpenAI resource endpoint.
         *
         * @param endpoint absolute HTTPS endpoint
         * @return this builder
         */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /**
         * Sets the Azure OpenAI resource endpoint.
         *
         * @param endpoint absolute HTTPS endpoint
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(Objects.requireNonNull(endpoint, "endpoint")));
        }

        /**
         * Sets the required Azure OpenAI deployment name.
         *
         * @param deployment deployment name
         * @return this builder
         */
        public Builder deployment(String deployment) {
            this.deployment = deployment;
            return this;
        }

        /**
         * Sets a service API version supported by the pinned SDK.
         *
         * @param apiVersion API version
         * @return this builder
         */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /**
         * Configures Azure OpenAI API-key authentication.
         *
         * @param apiKey non-blank API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = Secret.of(apiKey);
            return this;
        }

        /**
         * Configures a caller-owned Azure token credential.
         *
         * @param tokenCredential token credential
         * @return this builder
         */
        public Builder tokenCredential(TokenCredential tokenCredential) {
            this.tokenCredential = Objects.requireNonNull(tokenCredential, "tokenCredential");
            return this;
        }

        /**
         * Configures a provider-created default Azure credential.
         *
         * @return this builder
         */
        public Builder defaultAzureCredential() {
            return tokenCredential(new DefaultAzureCredentialBuilder().build());
        }

        /**
         * Configures a provider-created system-assigned managed-identity credential.
         *
         * @return this builder
         */
        public Builder managedIdentityCredential() {
            return tokenCredential(new ManagedIdentityCredentialBuilder().build());
        }

        /**
         * Configures a provider-created user-assigned managed-identity credential.
         *
         * @param clientId managed-identity client identifier
         * @return this builder
         */
        public Builder managedIdentityCredential(String clientId) {
            return tokenCredential(new ManagedIdentityCredentialBuilder()
                    .clientId(requireNonBlank(clientId, "clientId"))
                    .build());
        }

        /**
         * Sets the total provider request timeout.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the maximum provider retry count.
         *
         * @param maxRetries non-negative retry count
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the maximum number of undelivered streaming updates.
         *
         * @param maxBufferedUpdates positive capacity
         * @return this builder
         */
        public Builder maxBufferedUpdates(int maxBufferedUpdates) {
            this.maxBufferedUpdates = maxBufferedUpdates;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return client options
         */
        public AzureOpenAIChatClientOptions build() {
            return new AzureOpenAIChatClientOptions(this);
        }
    }

    private static final class Secret {
        private final String value;

        private Secret(String value) {
            this.value = requireNonBlank(value, "apiKey");
        }

        private static Secret of(String value) {
            return new Secret(value);
        }

        private String reveal() {
            return value;
        }

        @Override
        public String toString() {
            return "[REDACTED]";
        }
    }
}
