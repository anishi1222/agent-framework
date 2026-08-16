// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.mistral;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable Mistral Chat Completions configuration.
 */
public final class MistralChatClientOptions {
    /** Default hosted Mistral API endpoint. */
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.mistral.ai/v1/");

    /** Default number of undelivered streaming updates retained by a client. */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private final MistralSecret apiKey;

    private final MistralAuthenticationMode authenticationMode;

    private final String model;

    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final Duration timeout;

    private final int maxBufferedUpdates;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private MistralChatClientOptions(Builder builder) {
        apiKey = builder.apiKey;
        authenticationMode = Objects.requireNonNull(builder.authenticationMode, "authenticationMode");
        model = requireNonBlank(builder.model, "model");
        endpoint = normalizeEndpoint(Objects.requireNonNull(builder.endpoint, "endpoint"));
        allowedHosts = copyHosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        timeout = positive(builder.timeout, "timeout");
        maxBufferedUpdates = positive(builder.maxBufferedUpdates, "maxBufferedUpdates");
        maxRequestBytes = positive(builder.maxRequestBytes, "maxRequestBytes");
        maxResponseBytes = positive(builder.maxResponseBytes, "maxResponseBytes");
        maxEventBytes = positive(builder.maxEventBytes, "maxEventBytes");
        maxNestingDepth = positive(builder.maxNestingDepth, "maxNestingDepth");
        maxStringLength = positive(builder.maxStringLength, "maxStringLength");
        maxCollectionEntries = positive(builder.maxCollectionEntries, "maxCollectionEntries");
        maxConcurrentRequests = positive(builder.maxConcurrentRequests, "maxConcurrentRequests");
        validateEndpoint();
        validateAuthentication();
    }

    /**
     * Creates an options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns whether an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    /** Returns the authentication mode. */
    public MistralAuthenticationMode authenticationMode() {
        return authenticationMode;
    }

    /** Returns the default model identifier. */
    public String model() {
        return model;
    }

    /** Returns the normalized API endpoint ending in a slash. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the immutable remote-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether loopback HTTP is explicitly allowed. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns the per-request timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the maximum buffered streaming updates. */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    /** Returns the maximum encoded request size. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** Returns the maximum finite response size. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the maximum SSE event size. */
    public int maxEventBytes() {
        return maxEventBytes;
    }

    /** Returns the maximum JSON nesting depth. */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /** Returns the maximum JSON string length. */
    public int maxStringLength() {
        return maxStringLength;
    }

    /** Returns the maximum JSON collection entries. */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /** Returns the maximum concurrent provider calls. */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    MistralSecret apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "MistralChatClientOptions{apiKey="
                + (apiKey == null ? "<absent>" : "[REDACTED]")
                + ", authenticationMode="
                + authenticationMode
                + ", model='"
                + model
                + "', endpoint="
                + endpoint
                + ", allowedHosts="
                + allowedHosts
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + ", timeout="
                + timeout
                + ", maxBufferedUpdates="
                + maxBufferedUpdates
                + ", maxRequestBytes="
                + maxRequestBytes
                + ", maxResponseBytes="
                + maxResponseBytes
                + ", maxEventBytes="
                + maxEventBytes
                + ", maxNestingDepth="
                + maxNestingDepth
                + ", maxStringLength="
                + maxStringLength
                + ", maxCollectionEntries="
                + maxCollectionEntries
                + ", maxConcurrentRequests="
                + maxConcurrentRequests
                + '}';
    }

    private void validateEndpoint() {
        String scheme = endpoint.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must contain a host.");
        }
        if ("https".equals(scheme)) {
            if (!isLoopbackHost(host) && !allowedHosts.contains(host.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("endpoint host must be present in allowedHosts.");
            }
            return;
        }
        if (!"http".equals(scheme) || !allowInsecureLoopback || !isLoopbackHost(host)) {
            throw new IllegalArgumentException(
                    "endpoint must use HTTPS; loopback HTTP requires allowInsecureLoopback(true).");
        }
    }

    private void validateAuthentication() {
        if (authenticationMode == MistralAuthenticationMode.API_KEY && apiKey == null) {
            throw new IllegalArgumentException("API_KEY authentication requires an API key.");
        }
        if (authenticationMode == MistralAuthenticationMode.NONE && !isLoopbackHost(endpoint.getHost())) {
            throw new IllegalArgumentException("NONE authentication is restricted to loopback endpoints.");
        }
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.endsWith(".localhost")
                || normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    private static URI normalizeEndpoint(URI endpoint) {
        if (!endpoint.isAbsolute() || endpoint.getScheme() == null) {
            throw new IllegalArgumentException("endpoint must be absolute.");
        }
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain user info, query, or fragment.");
        }
        String value = endpoint.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static Set<String> copyHosts(Set<String> hosts) {
        Objects.requireNonNull(hosts, "allowedHosts");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String host : hosts) {
            copy.add(requireNonBlank(host, "allowedHosts element").toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(copy);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    /** Builds immutable {@link MistralChatClientOptions}. */
    public static final class Builder {
        private MistralSecret apiKey;

        private MistralAuthenticationMode authenticationMode = MistralAuthenticationMode.API_KEY;

        private String model;

        private URI endpoint = DEFAULT_ENDPOINT;

        private Set<String> allowedHosts = Set.of("api.mistral.ai");

        private boolean allowInsecureLoopback;

        private Duration timeout = Duration.ofSeconds(60);

        private int maxBufferedUpdates = DEFAULT_MAX_BUFFERED_UPDATES;

        private int maxRequestBytes = 2 * 1024 * 1024;

        private int maxResponseBytes = 8 * 1024 * 1024;

        private int maxEventBytes = 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 64;

        private Builder() {}

        /** Sets the bearer API key and selects API-key authentication. */
        public Builder apiKey(String apiKey) {
            this.apiKey = MistralSecret.of(apiKey);
            authenticationMode = MistralAuthenticationMode.API_KEY;
            return this;
        }

        /** Sets an already wrapped bearer API key. */
        public Builder apiKey(MistralSecret apiKey) {
            this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
            authenticationMode = MistralAuthenticationMode.API_KEY;
            return this;
        }

        /** Selects authentication behavior. */
        public Builder authenticationMode(MistralAuthenticationMode authenticationMode) {
            this.authenticationMode = Objects.requireNonNull(authenticationMode, "authenticationMode");
            return this;
        }

        /** Sets the default model identifier. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the API endpoint. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Sets the API endpoint. */
        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(Objects.requireNonNull(endpoint, "endpoint")));
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = Objects.requireNonNull(allowedHosts, "allowedHosts");
            return this;
        }

        /** Explicitly permits unencrypted loopback transport. */
        public Builder allowInsecureLoopback(boolean allowInsecureLoopback) {
            this.allowInsecureLoopback = allowInsecureLoopback;
            return this;
        }

        /** Sets the per-request timeout. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sets the maximum number of undelivered streaming updates. */
        public Builder maxBufferedUpdates(int maxBufferedUpdates) {
            this.maxBufferedUpdates = maxBufferedUpdates;
            return this;
        }

        /** Sets the maximum encoded request size. */
        public Builder maxRequestBytes(int maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
            return this;
        }

        /** Sets the maximum finite response size. */
        public Builder maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /** Sets the maximum encoded SSE event size. */
        public Builder maxEventBytes(int maxEventBytes) {
            this.maxEventBytes = maxEventBytes;
            return this;
        }

        /** Sets the maximum accepted JSON nesting depth. */
        public Builder maxNestingDepth(int maxNestingDepth) {
            this.maxNestingDepth = maxNestingDepth;
            return this;
        }

        /** Sets the maximum accepted JSON string length. */
        public Builder maxStringLength(int maxStringLength) {
            this.maxStringLength = maxStringLength;
            return this;
        }

        /** Sets the maximum accepted JSON collection entries. */
        public Builder maxCollectionEntries(int maxCollectionEntries) {
            this.maxCollectionEntries = maxCollectionEntries;
            return this;
        }

        /** Sets the maximum number of concurrent provider calls. */
        public Builder maxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
            return this;
        }

        /** Creates immutable options. */
        public MistralChatClientOptions build() {
            return new MistralChatClientOptions(this);
        }
    }
}
