// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundrylocal;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable process-neutral Foundry Local REST configuration.
 */
public final class FoundryLocalChatClientOptions {
    private final URI endpoint;

    private final String model;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final FoundryLocalSecret bearerToken;

    private final Duration timeout;

    private final int maxBufferedUpdates;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxEventBytes;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private FoundryLocalChatClientOptions(Builder builder) {
        endpoint = normalize(builder.endpoint);
        model = nonBlank(builder.model, "model");
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

    /** Returns the service root endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the loaded model identifier used for chat. */
    public String model() {
        return model;
    }

    /** Returns the remote-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether loopback HTTP is allowed. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns whether a reverse-proxy bearer token is configured. */
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

    /** Returns the maximum response bytes. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the maximum SSE event bytes. */
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

    FoundryLocalSecret bearerToken() {
        return bearerToken;
    }

    @Override
    public String toString() {
        return "FoundryLocalChatClientOptions{endpoint="
                + endpoint
                + ", model='"
                + model
                + "', allowedHosts="
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
        boolean loopback = isLoopback(host);
        if ("https".equals(scheme)) {
            if (!loopback && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Remote endpoint host must be in allowedHosts.");
            }
            if (!loopback && bearerToken == null) {
                throw new IllegalArgumentException("Remote Foundry Local endpoints require a bearer token.");
            }
            return;
        }
        if (!"http".equals(scheme) || !loopback || !allowInsecureLoopback) {
            throw new IllegalArgumentException(
                    "Foundry Local permits HTTP only for explicitly enabled loopback endpoints.");
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

    /** Builds immutable {@link FoundryLocalChatClientOptions}. */
    public static final class Builder {
        private URI endpoint;

        private String model;

        private Set<String> allowedHosts = Set.of("localhost", "127.0.0.1", "::1");

        private boolean allowInsecureLoopback = true;

        private FoundryLocalSecret bearerToken;

        private Duration timeout = Duration.ofMinutes(2);

        private int maxBufferedUpdates = 256;

        private int maxRequestBytes = 2 * 1024 * 1024;

        private int maxResponseBytes = 8 * 1024 * 1024;

        private int maxEventBytes = 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 32;

        private Builder() {}

        /** Sets the service root endpoint discovered from Foundry Local. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Sets the service root endpoint discovered from Foundry Local. */
        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(Objects.requireNonNull(endpoint, "endpoint")));
        }

        /** Sets the loaded model identifier. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = Objects.requireNonNull(allowedHosts, "allowedHosts");
            return this;
        }

        /** Selects whether loopback HTTP is allowed. */
        public Builder allowInsecureLoopback(boolean value) {
            allowInsecureLoopback = value;
            return this;
        }

        /** Sets a reverse-proxy bearer token. */
        public Builder bearerToken(String token) {
            bearerToken = FoundryLocalSecret.of(token);
            return this;
        }

        /** Sets a wrapped reverse-proxy bearer token. */
        public Builder bearerToken(FoundryLocalSecret token) {
            bearerToken = Objects.requireNonNull(token, "token");
            return this;
        }

        /** Sets the request timeout. */
        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value, "timeout");
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

        /** Sets the maximum SSE event bytes. */
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

        /** Sets the maximum concurrent calls. */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /** Creates immutable options. */
        public FoundryLocalChatClientOptions build() {
            return new FoundryLocalChatClientOptions(this);
        }
    }
}
