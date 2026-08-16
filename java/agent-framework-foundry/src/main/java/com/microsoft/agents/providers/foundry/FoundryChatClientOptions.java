// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines immutable Microsoft Foundry chat and agent-reference configuration.
 *
 * <p>A caller-supplied {@link TokenCredential} remains caller-owned. The provider never serializes,
 * logs, or closes it. Applications that call {@link Builder#tokenCredential(TokenCredential)} must
 * declare an Azure Identity or Azure Core compile dependency because Azure credential types are an
 * optional provider API surface.
 */
public final class FoundryChatClientOptions {
    /** Default total request timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /** Default maximum Azure pipeline retry count. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Default maximum number of undelivered streaming updates. */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private final URI projectEndpoint;

    private final String model;

    private final String agentName;

    private final String agentVersion;

    private final TokenCredential tokenCredential;

    private final FoundryContinuationMode continuationMode;

    private final String defaultConversationId;

    private final Duration timeout;

    private final int maxRetries;

    private final int maxBufferedUpdates;

    private FoundryChatClientOptions(Builder builder) {
        projectEndpoint = validateProjectEndpoint(builder.projectEndpoint);
        model = optionalNonBlank(builder.model, "model");
        agentName = optionalNonBlank(builder.agentName, "agentName");
        agentVersion = optionalNonBlank(builder.agentVersion, "agentVersion");
        if ((model == null) == (agentName == null)) {
            throw new IllegalArgumentException("Exactly one of model or agentName must be configured.");
        }
        if (agentVersion != null && agentName == null) {
            throw new IllegalArgumentException("agentVersion requires agentName.");
        }
        tokenCredential = Objects.requireNonNull(builder.tokenCredential, "tokenCredential");
        continuationMode = Objects.requireNonNull(builder.continuationMode, "continuationMode");
        defaultConversationId = optionalNonBlank(builder.defaultConversationId, "defaultConversationId");
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
     * Returns the validated Foundry project endpoint.
     *
     * @return project endpoint
     */
    public URI projectEndpoint() {
        return projectEndpoint;
    }

    /**
     * Returns the configured invocation surface.
     *
     * @return model or agent surface
     */
    public FoundrySurface surface() {
        return model == null ? FoundrySurface.AGENT : FoundrySurface.MODEL;
    }

    /**
     * Returns the optional model deployment.
     *
     * @return model deployment
     */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /**
     * Returns the optional existing Foundry agent name.
     *
     * @return agent name
     */
    public Optional<String> agentName() {
        return Optional.ofNullable(agentName);
    }

    /**
     * Returns the optional existing Foundry agent version.
     *
     * @return agent version
     */
    public Optional<String> agentVersion() {
        return Optional.ofNullable(agentVersion);
    }

    /**
     * Returns the continuation interpretation.
     *
     * @return continuation mode
     */
    public FoundryContinuationMode continuationMode() {
        return continuationMode;
    }

    /**
     * Returns an optional provider-owned default conversation identifier.
     *
     * @return default conversation identifier
     */
    public Optional<String> defaultConversationId() {
        return Optional.ofNullable(defaultConversationId);
    }

    /**
     * Returns the total request timeout.
     *
     * @return timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the maximum Azure pipeline retry count.
     *
     * @return retry count
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

    TokenCredential tokenCredential() {
        return tokenCredential;
    }

    String transportModel() {
        return model == null ? "agent:" + agentName : model;
    }

    @Override
    public String toString() {
        return "FoundryChatClientOptions{projectEndpoint="
                + projectEndpoint
                + ", surface="
                + surface()
                + ", model="
                + model
                + ", agentName="
                + agentName
                + ", agentVersion="
                + agentVersion
                + ", credential=[REDACTED], continuationMode="
                + continuationMode
                + ", defaultConversationId="
                + (defaultConversationId == null ? "<absent>" : "<present>")
                + ", timeout="
                + timeout
                + ", maxRetries="
                + maxRetries
                + ", maxBufferedUpdates="
                + maxBufferedUpdates
                + '}';
    }

    private static URI validateProjectEndpoint(URI value) {
        Objects.requireNonNull(value, "projectEndpoint");
        String path = value.getPath() == null ? "" : value.getPath();
        int marker = path.toLowerCase(java.util.Locale.ROOT).indexOf("/api/projects/");
        String project = marker < 0 ? null : path.substring(marker + "/api/projects/".length());
        if (project != null && project.endsWith("/")) {
            project = project.substring(0, project.length() - 1);
        }
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null
                || value.getHost().isBlank()
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || project == null
                || project.isBlank()
                || project.contains("/")) {
            throw new IllegalArgumentException(
                    "projectEndpoint must be an absolute HTTPS project URI ending in /api/projects/<project>.");
        }
        return value;
    }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
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

    /** Builds immutable {@link FoundryChatClientOptions}. */
    public static final class Builder {
        private URI projectEndpoint;

        private String model;

        private String agentName;

        private String agentVersion;

        private TokenCredential tokenCredential;

        private FoundryContinuationMode continuationMode = FoundryContinuationMode.CONVERSATION;

        private String defaultConversationId;

        private Duration timeout = DEFAULT_TIMEOUT;

        private int maxRetries = DEFAULT_MAX_RETRIES;

        private int maxBufferedUpdates = DEFAULT_MAX_BUFFERED_UPDATES;

        private Builder() {}

        /**
         * Sets the Foundry project endpoint.
         *
         * @param projectEndpoint absolute project endpoint
         * @return this builder
         */
        public Builder projectEndpoint(URI projectEndpoint) {
            this.projectEndpoint = Objects.requireNonNull(projectEndpoint, "projectEndpoint");
            return this;
        }

        /**
         * Sets the Foundry project endpoint.
         *
         * @param projectEndpoint absolute project endpoint
         * @return this builder
         */
        public Builder projectEndpoint(String projectEndpoint) {
            return projectEndpoint(URI.create(Objects.requireNonNull(projectEndpoint, "projectEndpoint")));
        }

        /**
         * Selects direct model-deployment Responses invocation.
         *
         * @param model model deployment name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Selects an existing versioned Foundry agent.
         *
         * @param agentName agent name
         * @return this builder
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Selects a specific existing Foundry agent version.
         *
         * @param agentVersion agent version
         * @return this builder
         */
        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
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
         * Sets how framework conversation identifiers map to Foundry Responses.
         *
         * @param continuationMode continuation mode
         * @return this builder
         */
        public Builder continuationMode(FoundryContinuationMode continuationMode) {
            this.continuationMode = Objects.requireNonNull(continuationMode, "continuationMode");
            return this;
        }

        /**
         * Sets a provider-owned default conversation identifier.
         *
         * @param conversationId conversation identifier
         * @return this builder
         */
        public Builder defaultConversationId(String conversationId) {
            this.defaultConversationId = conversationId;
            return this;
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
         * Sets the maximum Azure pipeline retry count.
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
         * @return Foundry client options
         */
        public FoundryChatClientOptions build() {
            return new FoundryChatClientOptions(this);
        }
    }
}
