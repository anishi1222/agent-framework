// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines immutable Amazon Bedrock Runtime Converse configuration.
 */
public final class BedrockChatClientOptions {
    private final String model;

    private final String region;

    private final URI endpointOverride;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final Duration timeout;

    private final int maxAttempts;

    private final int maxBufferedUpdates;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private BedrockChatClientOptions(Builder builder) {
        model = nonBlank(builder.model, "model");
        region = nonBlank(builder.region, "region");
        endpointOverride = builder.endpointOverride == null ? null : normalize(builder.endpointOverride);
        allowedHosts = copyHosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        timeout = positive(builder.timeout, "timeout");
        maxAttempts = positive(builder.maxAttempts, "maxAttempts");
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

    /** Returns the default model ID or inference-profile ARN. */
    public String model() {
        return model;
    }

    /** Returns the AWS region. */
    public String region() {
        return region;
    }

    /** Returns the optional endpoint override. */
    public Optional<URI> endpointOverride() {
        return Optional.ofNullable(endpointOverride);
    }

    /** Returns the endpoint host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether loopback HTTP endpoint overrides are enabled. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns the API call timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the SDK maximum attempt count. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns the streaming update bound. */
    public int maxBufferedUpdates() {
        return maxBufferedUpdates;
    }

    /** Returns the request-byte bound. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Returns the cumulative SDK-decoded mapped-response byte budget.
     *
     * <p>The transport also rejects a larger HTTP {@code Content-Length} when the AWS SDK exposes
     * that header. Because the SDK owns wire decoding, this value otherwise bounds the UTF-8 bytes
     * represented by mapped text, reasoning, tool JSON, usage, and metadata rather than raw
     * event-stream framing.
     */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the per-event SDK-decoded mapped-response byte budget. */
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

    @Override
    public String toString() {
        return "BedrockChatClientOptions{model='"
                + model
                + "', region='"
                + region
                + "', endpointOverride="
                + endpointOverride
                + ", allowedHosts="
                + allowedHosts
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + ", timeout="
                + timeout
                + ", maxAttempts="
                + maxAttempts
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
        if (endpointOverride == null) {
            return;
        }
        String host = endpointOverride.getHost();
        String scheme = endpointOverride.getScheme().toLowerCase(Locale.ROOT);
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpointOverride must contain a host.");
        }
        boolean loopback = isLoopback(host);
        if ("https".equals(scheme)) {
            if (!loopback && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Remote endpoint override host must be in allowedHosts.");
            }
            return;
        }
        if (!"http".equals(scheme) || !loopback || !allowInsecureLoopback) {
            throw new IllegalArgumentException(
                    "Bedrock endpoint overrides require HTTPS except explicit loopback tests.");
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
        Objects.requireNonNull(value, "endpointOverride");
        if (!value.isAbsolute()
                || value.getScheme() == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "endpointOverride must be absolute and contain no user info, query, or fragment.");
        }
        return value;
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

    /** Builds immutable {@link BedrockChatClientOptions}. */
    public static final class Builder {
        private String model;

        private String region = "us-east-1";

        private URI endpointOverride;

        private Set<String> allowedHosts = Set.of();

        private boolean allowInsecureLoopback;

        private Duration timeout = Duration.ofMinutes(2);

        private int maxAttempts = 3;

        private int maxBufferedUpdates = 256;

        private int maxRequestBytes = 8 * 1024 * 1024;

        private int maxResponseBytes = 16 * 1024 * 1024;

        private int maxEventBytes = 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 4 * 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 64;

        private Builder() {}

        /** Sets the model ID or inference-profile ARN. */
        public Builder model(String value) {
            model = value;
            return this;
        }

        /** Sets the AWS region. */
        public Builder region(String value) {
            region = value;
            return this;
        }

        /** Sets an endpoint override. */
        public Builder endpointOverride(URI value) {
            endpointOverride = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets an endpoint override. */
        public Builder endpointOverride(String value) {
            return endpointOverride(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Replaces the endpoint host allowlist. */
        public Builder allowedHosts(Set<String> value) {
            allowedHosts = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Enables loopback HTTP for protocol tests. */
        public Builder allowInsecureLoopback(boolean value) {
            allowInsecureLoopback = value;
            return this;
        }

        /** Sets the API call timeout. */
        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the SDK maximum attempt count. */
        public Builder maxAttempts(int value) {
            maxAttempts = value;
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

        /** Sets the cumulative SDK-decoded mapped-response byte budget. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets the per-event SDK-decoded mapped-response byte budget. */
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
        public BedrockChatClientOptions build() {
            return new BedrockChatClientOptions(this);
        }
    }
}
