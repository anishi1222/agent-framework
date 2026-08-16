// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Configures the redirect-free JDK Telegram Bot API client. */
public final class TelegramBotClientOptions {
    /** Default Telegram Bot API endpoint. */
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.telegram.org/");

    private static final Pattern BOT_TOKEN = Pattern.compile("[0-9]{1,20}:[A-Za-z0-9_-]{10,128}");

    private final TelegramSecret botToken;

    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final int maxRequestBytes;

    private final int maxResponseBytes;

    private final int maxWriteNestingDepth;

    private final int maxWriteStringLength;

    private final int maxWriteCollectionEntries;

    private final int maxNestingDepth;

    private final int maxStringLength;

    private final int maxCollectionEntries;

    private final int maxConcurrentRequests;

    private TelegramBotClientOptions(Builder builder) {
        botToken = Objects.requireNonNull(builder.botToken, "botToken");
        if (!BOT_TOKEN.matcher(botToken.value()).matches()) {
            throw new IllegalArgumentException("botToken does not match Telegram's Bot API token shape.");
        }
        endpoint = normalizeEndpoint(Objects.requireNonNull(builder.endpoint, "endpoint"));
        allowedHosts = copyHosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        connectTimeout = TelegramValidation.positive(builder.connectTimeout, "connectTimeout");
        requestTimeout = TelegramValidation.positive(builder.requestTimeout, "requestTimeout");
        maxRequestBytes = TelegramValidation.positive(builder.maxRequestBytes, "maxRequestBytes");
        maxResponseBytes = TelegramValidation.positive(builder.maxResponseBytes, "maxResponseBytes");
        maxWriteNestingDepth = TelegramValidation.positive(builder.maxWriteNestingDepth, "maxWriteNestingDepth");
        maxWriteStringLength = TelegramValidation.positive(builder.maxWriteStringLength, "maxWriteStringLength");
        maxWriteCollectionEntries =
                TelegramValidation.positive(builder.maxWriteCollectionEntries, "maxWriteCollectionEntries");
        maxNestingDepth = TelegramValidation.positive(builder.maxNestingDepth, "maxNestingDepth");
        maxStringLength = TelegramValidation.positive(builder.maxStringLength, "maxStringLength");
        maxCollectionEntries = TelegramValidation.positive(builder.maxCollectionEntries, "maxCollectionEntries");
        maxConcurrentRequests = TelegramValidation.positive(builder.maxConcurrentRequests, "maxConcurrentRequests");
        validateEndpoint();
    }

    /**
     * Creates an options builder.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the normalized Bot API endpoint ending in a slash.
     *
     * @return endpoint
     */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * Returns the immutable remote-host allowlist.
     *
     * @return allowed hosts
     */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Reports whether explicit loopback HTTP is enabled.
     *
     * @return loopback HTTP setting
     */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /**
     * Returns the connection timeout.
     *
     * @return connection timeout
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the complete request timeout.
     *
     * @return request timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the maximum encoded request bytes.
     *
     * @return byte limit
     */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /**
     * Returns the maximum response bytes.
     *
     * @return byte limit
     */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    int maxWriteNestingDepth() {
        return maxWriteNestingDepth;
    }

    int maxWriteStringLength() {
        return maxWriteStringLength;
    }

    int maxWriteCollectionEntries() {
        return maxWriteCollectionEntries;
    }

    /**
     * Returns the maximum JSON nesting depth.
     *
     * @return nesting limit
     */
    public int maxNestingDepth() {
        return maxNestingDepth;
    }

    /**
     * Returns the maximum JSON string and member-name length.
     *
     * @return string limit
     */
    public int maxStringLength() {
        return maxStringLength;
    }

    /**
     * Returns the maximum entries per JSON object or array.
     *
     * @return collection limit
     */
    public int maxCollectionEntries() {
        return maxCollectionEntries;
    }

    /**
     * Returns the maximum concurrent Bot API requests.
     *
     * @return concurrency limit
     */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    String botToken() {
        return botToken.value();
    }

    @Override
    public String toString() {
        return "TelegramBotClientOptions{botToken=[REDACTED], endpoint="
                + endpoint
                + ", allowedHosts="
                + allowedHosts
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + ", connectTimeout="
                + connectTimeout
                + ", requestTimeout="
                + requestTimeout
                + ", maxRequestBytes="
                + maxRequestBytes
                + ", maxResponseBytes="
                + maxResponseBytes
                + ", maxWriteNestingDepth="
                + maxWriteNestingDepth
                + ", maxWriteStringLength="
                + maxWriteStringLength
                + ", maxWriteCollectionEntries="
                + maxWriteCollectionEntries
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
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpoint must contain a host.");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            if (!isLoopbackHost(host) && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("endpoint host must be present in allowedHosts.");
            }
            return;
        }
        if (!"http".equals(scheme) || !allowInsecureLoopback || !isLoopbackHost(host)) {
            throw new IllegalArgumentException(
                    "endpoint must use HTTPS; loopback HTTP requires allowInsecureLoopback(true).");
        }
    }

    private static URI normalizeEndpoint(URI value) {
        if (!value.isAbsolute() || value.getScheme() == null) {
            throw new IllegalArgumentException("endpoint must be absolute.");
        }
        if (value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain user info, query, or fragment.");
        }
        String path = value.getRawPath();
        String normalizedPath = path == null || path.isEmpty() ? "/" : path;
        if (!normalizedPath.endsWith("/")) {
            normalizedPath += "/";
        }
        try {
            return new URI(value.getScheme(), value.getRawAuthority(), normalizedPath, null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("endpoint is invalid.", exception);
        }
    }

    private static Set<String> copyHosts(Set<String> values) {
        Objects.requireNonNull(values, "allowedHosts");
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            copy.add(TelegramValidation.nonBlank(value, "allowedHosts element").toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(copy);
    }

    static boolean isLoopbackHost(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.endsWith(".localhost")
                || normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    /** Builds immutable {@link TelegramBotClientOptions}. */
    public static final class Builder {
        private TelegramSecret botToken;

        private URI endpoint = DEFAULT_ENDPOINT;

        private Set<String> allowedHosts = Set.of("api.telegram.org");

        private boolean allowInsecureLoopback;

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofSeconds(30);

        private int maxRequestBytes = 32 * 1024;

        private int maxResponseBytes = 256 * 1024;

        private int maxWriteNestingDepth = 8;

        private int maxWriteStringLength = 16 * 1024;

        private int maxWriteCollectionEntries = 16;

        private int maxNestingDepth = 32;

        private int maxStringLength = 64 * 1024;

        private int maxCollectionEntries = 1024;

        private int maxConcurrentRequests = 32;

        private Builder() {}

        /**
         * Sets the Telegram Bot API token.
         *
         * @param value bot token
         * @return this builder
         */
        public Builder botToken(String value) {
            botToken = TelegramSecret.of(value);
            return this;
        }

        /**
         * Sets an already wrapped Telegram Bot API token.
         *
         * @param value bot token
         * @return this builder
         */
        public Builder botToken(TelegramSecret value) {
            botToken = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the Bot API endpoint.
         *
         * @param value absolute endpoint
         * @return this builder
         */
        public Builder endpoint(URI value) {
            endpoint = value;
            return this;
        }

        /**
         * Replaces the remote-host allowlist.
         *
         * @param values allowed host names
         * @return this builder
         */
        public Builder allowedHosts(Set<String> values) {
            allowedHosts = Set.copyOf(Objects.requireNonNull(values, "values"));
            return this;
        }

        /**
         * Allows or rejects explicit loopback HTTP endpoints.
         *
         * @param value loopback HTTP setting
         * @return this builder
         */
        public Builder allowInsecureLoopback(boolean value) {
            allowInsecureLoopback = value;
            return this;
        }

        /**
         * Sets the connection timeout.
         *
         * @param value positive timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        /**
         * Sets the complete request timeout.
         *
         * @param value positive timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        /**
         * Sets the maximum encoded request bytes.
         *
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        /**
         * Sets the maximum response bytes.
         *
         * @param value positive byte limit
         * @return this builder
         */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /**
         * Sets the maximum JSON nesting depth.
         *
         * @param value positive nesting limit
         * @return this builder
         */
        public Builder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        /**
         * Sets the maximum JSON string and member-name length.
         *
         * @param value positive string limit
         * @return this builder
         */
        public Builder maxStringLength(int value) {
            maxStringLength = value;
            return this;
        }

        /**
         * Sets the maximum entries per JSON object or array.
         *
         * @param value positive collection limit
         * @return this builder
         */
        public Builder maxCollectionEntries(int value) {
            maxCollectionEntries = value;
            return this;
        }

        /**
         * Sets the maximum concurrent Bot API requests.
         *
         * @param value positive concurrency limit
         * @return this builder
         */
        public Builder maxConcurrentRequests(int value) {
            maxConcurrentRequests = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public TelegramBotClientOptions build() {
            return new TelegramBotClientOptions(this);
        }
    }
}
