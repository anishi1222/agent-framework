// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.ollama;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable Ollama {@code /api/chat} configuration.
 */
public final class OllamaChatClientOptions {
    /** Standard local Ollama endpoint. */
    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:11434/");

    /** Default streaming update bound. */
    public static final int DEFAULT_MAX_BUFFERED_UPDATES = 256;

    private final String model;

    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final OllamaSecret bearerToken;

    private final Duration timeout;

    private final int maxBufferedUpdates;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private OllamaChatClientOptions(Builder builder) {
        model = nonBlank(builder.model, "model");
        endpoint = normalize(builder.endpoint);
        allowedHosts = copyHosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        bearerToken = builder.bearerToken;
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
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the default model. */
    public String model() {
        return model;
    }

    /** Returns the normalized endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the immutable remote-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether loopback HTTP is enabled. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns whether a bearer token is configured. */
    public boolean hasBearerToken() {
        return bearerToken != null;
    }

    /** Returns the request timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the maximum buffered streaming updates. */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    /** Returns the maximum request bytes. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** Returns the maximum finite response bytes. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the maximum NDJSON event bytes. */
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

    /** Returns the maximum concurrent calls. */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    OllamaSecret bearerToken() {
        return bearerToken;
    }

    @Override
    public String toString() {
        return "OllamaChatClientOptions{model='"
                + model
                + "', endpoint="
                + endpoint
                + ", allowedHosts="
                + allowedHosts
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + ", bearerToken="
                + (bearerToken == null ? "<absent>" : "[REDACTED]")
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
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must contain a host.");
        }
        if ("https".equals(scheme)) {
            if (!isLoopback(host) && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Remote endpoint host must be present in allowedHosts.");
            }
            return;
        }
        if (!"http".equals(scheme) || !allowInsecureLoopback || !isLoopback(host)) {
            throw new IllegalArgumentException("Remote Ollama endpoints require HTTPS and an explicit host allowlist.");
        }
    }

    static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || value.endsWith(".localhost")
                || "127.0.0.1".equals(value)
                || value.startsWith("127.")
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static URI normalize(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getScheme() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be absolute and contain no user info, query, or fragment.");
        }
        String value = endpoint.toString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    private static Set<String> copyHosts(Set<String> hosts) {
        Objects.requireNonNull(hosts, "allowedHosts");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        hosts.forEach(host -> copy.add(nonBlank(host, "allowedHosts element").toLowerCase(Locale.ROOT)));
        return Set.copyOf(copy);
    }

    private static String nonBlank(String value, String name) {
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

    /** Builds immutable {@link OllamaChatClientOptions}. */
    public static final class Builder {
        private String model;

        private URI endpoint = DEFAULT_ENDPOINT;

        private Set<String> allowedHosts = Set.of("localhost", "127.0.0.1", "::1");

        private boolean allowInsecureLoopback = true;

        private OllamaSecret bearerToken;

        private Duration timeout = Duration.ofMinutes(2);

        private int maxBufferedUpdates = DEFAULT_MAX_BUFFERED_UPDATES;

        private int maxRequestBytes = 2 * 1024 * 1024;

        private int maxResponseBytes = 8 * 1024 * 1024;

        private int maxEventBytes = 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 32;

        private Builder() {}

        /** Sets the default model. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the Ollama endpoint. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Sets the Ollama endpoint. */
        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(Objects.requireNonNull(endpoint, "endpoint")));
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = Objects.requireNonNull(allowedHosts, "allowedHosts");
            return this;
        }

        /** Selects whether unencrypted loopback transport is allowed. */
        public Builder allowInsecureLoopback(boolean allowInsecureLoopback) {
            this.allowInsecureLoopback = allowInsecureLoopback;
            return this;
        }

        /** Sets an optional bearer token for a protected compatible endpoint. */
        public Builder bearerToken(String bearerToken) {
            this.bearerToken = OllamaSecret.of(bearerToken);
            return this;
        }

        /** Sets an optional wrapped bearer token. */
        public Builder bearerToken(OllamaSecret bearerToken) {
            this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
            return this;
        }

        /** Sets the request timeout. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Sets the streaming update bound. */
        public Builder maxBufferedUpdates(int value) {
            maxBufferedUpdates = value;
            return this;
        }

        /** Sets the maximum request bytes. */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        /** Sets the maximum response bytes. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets the maximum NDJSON event bytes. */
        public Builder maxEventBytes(int value) {
            maxEventBytes = value;
            return this;
        }

        /** Sets the maximum JSON nesting depth. */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /** Sets the maximum JSON string length. */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /** Sets the maximum JSON collection entries. */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /** Sets the maximum concurrent requests. */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /** Creates immutable options. */
        public OllamaChatClientOptions build() {
            return new OllamaChatClientOptions(this);
        }
    }
}
