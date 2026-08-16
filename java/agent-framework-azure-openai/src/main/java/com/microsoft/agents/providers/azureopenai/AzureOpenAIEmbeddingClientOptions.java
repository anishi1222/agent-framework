// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureopenai;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.microsoft.agents.providers.openai.OpenAIEmbeddingClientOptions;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Defines immutable Azure OpenAI embedding client configuration. */
public final class AzureOpenAIEmbeddingClientOptions {
    /** Default API version supported by the pinned classic Azure OpenAI SDK. */
    public static final String DEFAULT_API_VERSION = "2025-01-01-preview";

    /** Default total request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final URI endpoint;

    private final String deployment;

    private final String apiVersion;

    private final Secret apiKey;

    private final TokenCredential tokenCredential;

    private final Duration timeout;

    private final int maxRetries;

    private final int maxBatchSize;

    private AzureOpenAIEmbeddingClientOptions(Builder builder) {
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
        if (builder.maxBatchSize <= 0 || builder.maxBatchSize > OpenAIEmbeddingClientOptions.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "maxBatchSize must be between 1 and " + OpenAIEmbeddingClientOptions.MAX_BATCH_SIZE + ".");
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

    /** Returns the Azure OpenAI resource endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the default embedding deployment. */
    public String deployment() {
        return deployment;
    }

    /** Returns the configured service API version. */
    public String apiVersion() {
        return apiVersion;
    }

    /** Returns the authentication mode without exposing credentials. */
    public AzureOpenAIAuthenticationMode authenticationMode() {
        return apiKey == null ? AzureOpenAIAuthenticationMode.TOKEN_CREDENTIAL : AzureOpenAIAuthenticationMode.API_KEY;
    }

    /** Returns the total request timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the maximum provider retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the automatic request batch size. */
    public int maxBatchSize() {
        return maxBatchSize;
    }

    String apiKey() {
        return apiKey == null ? null : apiKey.reveal();
    }

    TokenCredential tokenCredential() {
        return tokenCredential;
    }

    @Override
    public String toString() {
        return "AzureOpenAIEmbeddingClientOptions{endpoint="
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
                + ", maxBatchSize="
                + maxBatchSize
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
            case "2022-12-01",
                    "2023-05-15",
                    "2023-06-01-preview",
                    "2023-07-01-preview",
                    "2024-02-01",
                    "2024-02-15-preview",
                    "2024-03-01-preview",
                    "2024-04-01-preview",
                    "2024-05-01-preview",
                    "2024-06-01",
                    "2024-07-01-preview",
                    "2024-08-01-preview",
                    "2024-09-01-preview",
                    "2024-10-01-preview",
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

    /** Builds immutable {@link AzureOpenAIEmbeddingClientOptions}. */
    public static final class Builder {
        private URI endpoint;

        private String deployment;

        private String apiVersion = DEFAULT_API_VERSION;

        private Secret apiKey;

        private TokenCredential tokenCredential;

        private Duration timeout = DEFAULT_TIMEOUT;

        private int maxRetries = 2;

        private int maxBatchSize = OpenAIEmbeddingClientOptions.MAX_BATCH_SIZE;

        private Builder() {}

        /** Sets the Azure OpenAI resource endpoint. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Sets the Azure OpenAI resource endpoint. */
        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(Objects.requireNonNull(endpoint, "endpoint")));
        }

        /** Sets the required default embedding deployment. */
        public Builder deployment(String deployment) {
            this.deployment = deployment;
            return this;
        }

        /** Sets the Azure OpenAI service API version. */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /** Configures API-key authentication. */
        public Builder apiKey(String apiKey) {
            this.apiKey = Secret.of(apiKey);
            return this;
        }

        /** Configures a caller-owned Azure token credential. */
        public Builder tokenCredential(TokenCredential tokenCredential) {
            this.tokenCredential = Objects.requireNonNull(tokenCredential, "tokenCredential");
            return this;
        }

        /** Configures a provider-created default Azure credential. */
        public Builder defaultAzureCredential() {
            return tokenCredential(new DefaultAzureCredentialBuilder().build());
        }

        /** Configures a provider-created system-assigned managed identity. */
        public Builder managedIdentityCredential() {
            return tokenCredential(new ManagedIdentityCredentialBuilder().build());
        }

        /** Configures a provider-created user-assigned managed identity. */
        public Builder managedIdentityCredential(String clientId) {
            return tokenCredential(new ManagedIdentityCredentialBuilder()
                    .clientId(requireNonBlank(clientId, "clientId"))
                    .build());
        }

        /** Sets the total provider request timeout. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sets the maximum provider retry count. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /** Sets the maximum values sent in one request. */
        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /** Creates immutable client options. */
        public AzureOpenAIEmbeddingClientOptions build() {
            return new AzureOpenAIEmbeddingClientOptions(this);
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
    }
}
