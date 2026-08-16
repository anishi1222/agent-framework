// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/** Defines immutable Azure AI Agents Persistent client limits and ownership. */
public final class AzureAIPersistentClientOptions {
    /** Default total operation timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** Default initial polling delay. */
    public static final Duration DEFAULT_INITIAL_POLL_DELAY = Duration.ofMillis(250);
    /** Default maximum polling delay. */
    public static final Duration DEFAULT_MAX_POLL_DELAY = Duration.ofSeconds(5);
    /** Default page size. */
    public static final int DEFAULT_PAGE_SIZE = 100;
    /** Default maximum buffered streaming updates. */
    public static final int DEFAULT_MAX_BUFFERED_EVENTS = 256;

    private final URI endpoint;
    private final AzureAuthenticationProvider authenticationProvider;
    private final Duration timeout;
    private final Duration initialPollDelay;
    private final Duration maxPollDelay;
    private final double pollJitter;
    private final int maxRetries;
    private final int maxPageSize;
    private final int maxBufferedEvents;
    private final ScheduledExecutorService scheduler;

    private AzureAIPersistentClientOptions(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint);
        authenticationProvider = Objects.requireNonNull(builder.authenticationProvider, "authenticationProvider");
        timeout = positive(builder.timeout, "timeout");
        initialPollDelay = positive(builder.initialPollDelay, "initialPollDelay");
        maxPollDelay = positive(builder.maxPollDelay, "maxPollDelay");
        if (maxPollDelay.compareTo(initialPollDelay) < 0) {
            throw new IllegalArgumentException("maxPollDelay must not be less than initialPollDelay.");
        }
        if (!Double.isFinite(builder.pollJitter) || builder.pollJitter < 0 || builder.pollJitter > 1) {
            throw new IllegalArgumentException("pollJitter must be between 0 and 1.");
        }
        pollJitter = builder.pollJitter;
        if (builder.maxRetries < 0 || builder.maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10.");
        }
        maxRetries = builder.maxRetries;
        if (builder.maxPageSize <= 0 || builder.maxPageSize > 1000) {
            throw new IllegalArgumentException("maxPageSize must be between 1 and 1000.");
        }
        maxPageSize = builder.maxPageSize;
        if (builder.maxBufferedEvents <= 0 || builder.maxBufferedEvents > 65_536) {
            throw new IllegalArgumentException("maxBufferedEvents must be between 1 and 65536.");
        }
        maxBufferedEvents = builder.maxBufferedEvents;
        scheduler = builder.scheduler;
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the validated project endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the caller-owned authentication provider. */
    public AzureAuthenticationProvider authenticationProvider() {
        return authenticationProvider;
    }

    /** Returns the total operation timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Returns the initial polling delay. */
    public Duration initialPollDelay() {
        return initialPollDelay;
    }

    /** Returns the maximum polling delay. */
    public Duration maxPollDelay() {
        return maxPollDelay;
    }

    /** Returns the polling jitter fraction. */
    public double pollJitter() {
        return pollJitter;
    }

    /** Returns the bounded Azure pipeline retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the maximum caller-requested page size. */
    public int maxPageSize() {
        return maxPageSize;
    }

    /** Returns the maximum undelivered streaming event count. */
    public int maxBufferedEvents() {
        return maxBufferedEvents;
    }

    /** Returns the optional caller-owned scheduler. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        String path = value.getPath() == null ? "" : value.getPath();
        int marker = path.toLowerCase(java.util.Locale.ROOT).indexOf("/api/projects/");
        String project = marker < 0 ? null : path.substring(marker + "/api/projects/".length());
        if (project != null && project.endsWith("/")) {
            project = project.substring(0, project.length() - 1);
        }
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || project == null
                || project.isBlank()
                || project.contains("/")) {
            throw new IllegalArgumentException(
                    "endpoint must be an HTTPS Foundry project URI ending in /api/projects/<project>.");
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

    /** Builds immutable persistent client options. */
    public static final class Builder {
        private URI endpoint;
        private AzureAuthenticationProvider authenticationProvider;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration initialPollDelay = DEFAULT_INITIAL_POLL_DELAY;
        private Duration maxPollDelay = DEFAULT_MAX_POLL_DELAY;
        private double pollJitter = 0.2;
        private int maxRetries = 2;
        private int maxPageSize = DEFAULT_PAGE_SIZE;
        private int maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;
        private ScheduledExecutorService scheduler;

        private Builder() {}

        /** Sets the Foundry project endpoint. */
        public Builder endpoint(URI value) {
            endpoint = value;
            return this;
        }

        /** Sets the Foundry project endpoint. */
        public Builder endpoint(String value) {
            return endpoint(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Sets a caller-owned authentication provider. */
        public Builder authenticationProvider(AzureAuthenticationProvider value) {
            authenticationProvider = value;
            return this;
        }

        /** Sets the total operation timeout. */
        public Builder timeout(Duration value) {
            timeout = value;
            return this;
        }

        /** Sets the initial polling delay. */
        public Builder initialPollDelay(Duration value) {
            initialPollDelay = value;
            return this;
        }

        /** Sets the maximum polling delay. */
        public Builder maxPollDelay(Duration value) {
            maxPollDelay = value;
            return this;
        }

        /** Sets the polling jitter fraction. */
        public Builder pollJitter(double value) {
            pollJitter = value;
            return this;
        }

        /** Sets the maximum Azure pipeline retry count. */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /** Sets the maximum page size. */
        public Builder maxPageSize(int value) {
            maxPageSize = value;
            return this;
        }

        /** Sets the maximum undelivered stream event count. */
        public Builder maxBufferedEvents(int value) {
            maxBufferedEvents = value;
            return this;
        }

        /** Sets a caller-owned polling scheduler, which the client never closes. */
        public Builder scheduler(ScheduledExecutorService value) {
            scheduler = value;
            return this;
        }

        /** Creates immutable options. */
        public AzureAIPersistentClientOptions build() {
            return new AzureAIPersistentClientOptions(this);
        }
    }
}
