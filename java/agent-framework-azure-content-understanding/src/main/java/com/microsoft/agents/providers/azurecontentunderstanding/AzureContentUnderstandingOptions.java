// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azurecontentunderstanding;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Defines immutable Content Understanding service, polling, and payload limits. */
public final class AzureContentUnderstandingOptions {
    private final URI endpoint;
    private final AzureAuthenticationProvider authenticationProvider;
    private final Duration operationTimeout;
    private final Duration pollInterval;
    private final int maxRetries;
    private final int maxInputs;
    private final int maxInputBytes;
    private final int maxPageSize;
    private final int maxPages;
    private final int maxJsonBytes;

    private AzureContentUnderstandingOptions(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint);
        authenticationProvider = Objects.requireNonNull(builder.authenticationProvider, "authenticationProvider");
        operationTimeout = positive(builder.operationTimeout, "operationTimeout");
        pollInterval = positive(builder.pollInterval, "pollInterval");
        maxRetries = bounded(builder.maxRetries, 0, 10, "maxRetries");
        maxInputs = bounded(builder.maxInputs, 1, 1000, "maxInputs");
        maxInputBytes = bounded(builder.maxInputBytes, 1, 500 * 1024 * 1024, "maxInputBytes");
        maxPageSize = bounded(builder.maxPageSize, 1, 1000, "maxPageSize");
        maxPages = bounded(builder.maxPages, 1, 1000, "maxPages");
        maxJsonBytes = bounded(builder.maxJsonBytes, 1, 64 * 1024 * 1024, "maxJsonBytes");
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the verified service endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the caller-owned authentication provider. */
    public AzureAuthenticationProvider authenticationProvider() {
        return authenticationProvider;
    }

    /** Returns the total operation timeout. */
    public Duration operationTimeout() {
        return operationTimeout;
    }

    /** Returns the polling interval. */
    public Duration pollInterval() {
        return pollInterval;
    }

    /** Returns the Azure pipeline retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the maximum inputs per analysis. */
    public int maxInputs() {
        return maxInputs;
    }

    /** Returns the maximum aggregate byte input size. */
    public int maxInputBytes() {
        return maxInputBytes;
    }

    /** Returns the maximum page size. */
    public int maxPageSize() {
        return maxPageSize;
    }

    /** Returns the maximum resource pages. */
    public int maxPages() {
        return maxPages;
    }

    /** Returns the maximum analyzer/result JSON bytes. */
    public int maxJsonBytes() {
        return maxJsonBytes;
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        String host = value.getHost();
        String path = value.getPath() == null ? "" : value.getPath();
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || host == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || !(path.isEmpty() || path.equals("/"))
                || !isAzureContentHost(host)) {
            throw new IllegalArgumentException(
                    "endpoint must be an Azure Foundry or Cognitive Services HTTPS resource origin.");
        }
        return value;
    }

    private static boolean isAzureContentHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.endsWith(".services.ai.azure.com")
                || value.endsWith(".cognitiveservices.azure.com")
                || value.endsWith(".services.ai.azure.us")
                || value.endsWith(".cognitiveservices.azure.us")
                || value.endsWith(".services.ai.azure.cn")
                || value.endsWith(".cognitiveservices.azure.cn");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    /** Builds immutable Content Understanding options. */
    public static final class Builder {
        private URI endpoint;
        private AzureAuthenticationProvider authenticationProvider;
        private Duration operationTimeout = Duration.ofMinutes(10);
        private Duration pollInterval = Duration.ofSeconds(3);
        private int maxRetries = 2;
        private int maxInputs = 100;
        private int maxInputBytes = 50 * 1024 * 1024;
        private int maxPageSize = 100;
        private int maxPages = 100;
        private int maxJsonBytes = 8 * 1024 * 1024;

        private Builder() {}

        /** Sets the service endpoint. */
        public Builder endpoint(URI value) {
            endpoint = value;
            return this;
        }

        /** Sets the service endpoint. */
        public Builder endpoint(String value) {
            return endpoint(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Sets the caller-owned authentication provider. */
        public Builder authenticationProvider(AzureAuthenticationProvider value) {
            authenticationProvider = value;
            return this;
        }

        /** Sets the total operation timeout. */
        public Builder operationTimeout(Duration value) {
            operationTimeout = value;
            return this;
        }

        /** Sets the polling interval. */
        public Builder pollInterval(Duration value) {
            pollInterval = value;
            return this;
        }

        /** Sets the Azure pipeline retry count. */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /** Sets the maximum inputs per analysis. */
        public Builder maxInputs(int value) {
            maxInputs = value;
            return this;
        }

        /** Sets the maximum aggregate byte input size. */
        public Builder maxInputBytes(int value) {
            maxInputBytes = value;
            return this;
        }

        /** Sets the maximum resource page size. */
        public Builder maxPageSize(int value) {
            maxPageSize = value;
            return this;
        }

        /** Sets the maximum resource pages. */
        public Builder maxPages(int value) {
            maxPages = value;
            return this;
        }

        /** Sets the maximum analyzer/result JSON size. */
        public Builder maxJsonBytes(int value) {
            maxJsonBytes = value;
            return this;
        }

        /** Creates immutable options. */
        public AzureContentUnderstandingOptions build() {
            return new AzureContentUnderstandingOptions(this);
        }
    }
}
