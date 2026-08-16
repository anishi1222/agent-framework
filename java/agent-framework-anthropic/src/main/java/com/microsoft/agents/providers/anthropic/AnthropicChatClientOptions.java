// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable Anthropic Messages API configuration.
 */
public final class AnthropicChatClientOptions {
    /** Official Anthropic API endpoint. */
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.anthropic.com/");

    private final AnthropicSecret apiKey;

    private final String model;

    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final Duration timeout;

    private final int maxRetries;

    private final int defaultMaxTokens;

    private final int maxBufferedUpdates;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private AnthropicChatClientOptions(Builder builder) {
        apiKey = builder.apiKey;
        model = nonBlank(builder.model, "model");
        endpoint = normalize(builder.endpoint);
        allowedHosts = copyHosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        timeout = positive(builder.timeout, "timeout");
        maxRetries = nonNegative(builder.maxRetries, "maxRetries");
        defaultMaxTokens = positive(builder.defaultMaxTokens, "defaultMaxTokens");
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

    /** Returns whether an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    /** Returns the default model. */
    public String model() {
        return model;
    }

    /** Returns the normalized endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the remote-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether loopback HTTP is allowed. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns the request timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the SDK retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the default required output-token limit. */
    public int defaultMaxTokens() {
        return defaultMaxTokens;
    }

    /** Returns the streaming update bound. */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    /** Returns the request-byte bound. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** Returns the response-byte bound. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the event-byte bound. */
    public int maxEventBytes() {
        return maxEventBytes;
    }

    /** Returns the JSON nesting bound. */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /** Returns the JSON string bound. */
    public int maxStringLength() {
        return maxStringLength;
    }

    /** Returns the JSON collection bound. */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /** Returns the concurrent-request bound. */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    AnthropicSecret apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "AnthropicChatClientOptions{apiKey="
                + (apiKey == null ? "<absent>" : "[REDACTED]")
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
                + ", maxRetries="
                + maxRetries
                + ", defaultMaxTokens="
                + defaultMaxTokens
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
        String host = endpoint.getHost();
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must contain a host.");
        }
        boolean loopback = isLoopback(host);
        if ("https".equals(scheme)) {
            if (!loopback && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Remote endpoint host must be in allowedHosts.");
            }
            return;
        }
        if (!"http".equals(scheme) || !loopback || !allowInsecureLoopback) {
            throw new IllegalArgumentException("Anthropic endpoints require HTTPS except explicit loopback tests.");
        }
    }

    static boolean isLoopback(String host) {
        String value = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || value.endsWith(".localhost")
                || value.startsWith("127.")
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static URI normalize(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!value.isAbsolute()
                || value.getScheme() == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "endpoint must be absolute and contain no user info, query, or fragment.");
        }
        String endpoint = value.toString();
        return URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
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

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
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

    /** Builds immutable {@link AnthropicChatClientOptions}. */
    public static final class Builder {
        private AnthropicSecret apiKey;

        private String model;

        private URI endpoint = DEFAULT_ENDPOINT;

        private Set<String> allowedHosts = Set.of("api.anthropic.com");

        private boolean allowInsecureLoopback;

        private Duration timeout = Duration.ofSeconds(60);

        private int maxRetries = 2;

        private int defaultMaxTokens = 1024;

        private int maxBufferedUpdates = 256;

        private int maxRequestBytes = 4 * 1024 * 1024;

        private int maxResponseBytes = 16 * 1024 * 1024;

        private int maxEventBytes = 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 4 * 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 64;

        private Builder() {}

        /** Sets the API key. */
        public Builder apiKey(String value) {
            apiKey = AnthropicSecret.of(value);
            return this;
        }

        /** Sets a wrapped API key. */
        public Builder apiKey(AnthropicSecret value) {
            apiKey = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the default model. */
        public Builder model(String value) {
            model = value;
            return this;
        }

        /** Sets the API endpoint. */
        public Builder endpoint(URI value) {
            endpoint = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the API endpoint. */
        public Builder endpoint(String value) {
            return endpoint(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> value) {
            allowedHosts = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Enables unencrypted loopback test transport. */
        public Builder allowInsecureLoopback(boolean value) {
            allowInsecureLoopback = value;
            return this;
        }

        /** Sets the request timeout. */
        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the SDK retry count. */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /** Sets the default maximum output tokens. */
        public Builder defaultMaxTokens(int value) {
            defaultMaxTokens = value;
            return this;
        }

        /** Sets the streaming update bound. */
        public Builder maxBufferedUpdates(int value) {
            maxBufferedUpdates = value;
            return this;
        }

        /** Sets the request-byte bound. */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        /** Sets the response-byte bound. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets the event-byte bound. */
        public Builder maxEventBytes(int value) {
            maxEventBytes = value;
            return this;
        }

        /** Sets the JSON nesting bound. */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /** Sets the JSON string bound. */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /** Sets the JSON collection bound. */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /** Sets the concurrent-request bound. */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /** Creates immutable options. */
        public AnthropicChatClientOptions build() {
            return new AnthropicChatClientOptions(this);
        }
    }
}
