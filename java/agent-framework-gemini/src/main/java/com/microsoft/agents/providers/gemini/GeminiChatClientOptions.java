// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable Google Gen AI SDK configuration.
 */
public final class GeminiChatClientOptions {
    /** Gemini Developer API service root. */
    public static final URI DEFAULT_ENDPOINT = URI.create("https://generativelanguage.googleapis.com/");

    private final GeminiAuthenticationMode authenticationMode;

    private final GeminiSecret apiKey;

    private final String project;

    private final String location;

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

    private GeminiChatClientOptions(Builder builder) {
        authenticationMode = Objects.requireNonNull(builder.authenticationMode, "authenticationMode");
        apiKey = builder.apiKey;
        project = optional(builder.project, "project");
        location = optional(builder.location, "location");
        model = nonBlank(builder.model, "model");
        endpoint = normalize(builder.endpoint);
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
        validateAuth();
        validateEndpoint();
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the authentication mode. */
    public GeminiAuthenticationMode authenticationMode() {
        return authenticationMode;
    }

    /** Returns whether an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null;
    }

    /** Returns the optional Vertex project. */
    public String project() {
        return project;
    }

    /** Returns the optional Vertex location. */
    public String location() {
        return location;
    }

    /** Returns the default model. */
    public String model() {
        return model;
    }

    /** Returns the service root. */
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

    GeminiSecret apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "GeminiChatClientOptions{authenticationMode="
                + authenticationMode
                + ", apiKey="
                + (apiKey == null ? "<absent>" : "[REDACTED]")
                + ", project="
                + project
                + ", location="
                + location
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

    private void validateAuth() {
        if (authenticationMode == GeminiAuthenticationMode.API_KEY && apiKey == null) {
            throw new IllegalArgumentException("API_KEY authentication requires an API key.");
        }
        if (authenticationMode == GeminiAuthenticationMode.VERTEX_APPLICATION_DEFAULT
                && (project == null || location == null)) {
            throw new IllegalArgumentException("Vertex authentication requires project and location.");
        }
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
            throw new IllegalArgumentException("Gemini endpoints require HTTPS except explicit loopback SDK tests.");
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

    private static Set<String> copyHosts(Set<String> values) {
        Objects.requireNonNull(values, "allowedHosts");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        values.forEach(value -> copy.add(nonBlank(value, "allowedHosts element").toLowerCase(Locale.ROOT)));
        return Set.copyOf(copy);
    }

    private static String optional(String value, String name) {
        return value == null ? null : nonBlank(value, name);
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

    /** Builds immutable {@link GeminiChatClientOptions}. */
    public static final class Builder {
        private GeminiAuthenticationMode authenticationMode = GeminiAuthenticationMode.API_KEY;

        private GeminiSecret apiKey;

        private String project;

        private String location;

        private String model;

        private URI endpoint = DEFAULT_ENDPOINT;

        private Set<String> allowedHosts = Set.of("generativelanguage.googleapis.com", "aiplatform.googleapis.com");

        private boolean allowInsecureLoopback;

        private Duration timeout = Duration.ofMinutes(2);

        private int maxBufferedUpdates = 256;

        private int maxRequestBytes = 8 * 1024 * 1024;

        private int maxResponseBytes = 16 * 1024 * 1024;

        private int maxEventBytes = 2 * 1024 * 1024;

        private int maxNestingDepth = 64;

        private int maxStringLength = 4 * 1024 * 1024;

        private int maxCollectionEntries = 100_000;

        private int maxConcurrentRequests = 64;

        private Builder() {}

        /** Sets API-key authentication. */
        public Builder apiKey(String value) {
            apiKey = GeminiSecret.of(value);
            authenticationMode = GeminiAuthenticationMode.API_KEY;
            return this;
        }

        /** Sets a wrapped API key. */
        public Builder apiKey(GeminiSecret value) {
            apiKey = Objects.requireNonNull(value, "value");
            authenticationMode = GeminiAuthenticationMode.API_KEY;
            return this;
        }

        /** Selects authentication. */
        public Builder authenticationMode(GeminiAuthenticationMode value) {
            authenticationMode = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the Vertex project. */
        public Builder project(String value) {
            project = value;
            return this;
        }

        /** Sets the Vertex location. */
        public Builder location(String value) {
            location = value;
            return this;
        }

        /** Sets the default model. */
        public Builder model(String value) {
            model = value;
            return this;
        }

        /** Sets a custom service root. */
        public Builder endpoint(URI value) {
            endpoint = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets a custom service root. */
        public Builder endpoint(String value) {
            return endpoint(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> value) {
            allowedHosts = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Enables loopback HTTP for SDK protocol tests. */
        public Builder allowInsecureLoopback(boolean value) {
            allowInsecureLoopback = value;
            return this;
        }

        /** Sets the request timeout. */
        public Builder timeout(Duration value) {
            timeout = Objects.requireNonNull(value, "value");
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
        public GeminiChatClientOptions build() {
            return new GeminiChatClientOptions(this);
        }
    }
}
