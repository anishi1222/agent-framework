// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation.foundry;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/** Defines immutable Foundry evaluation limits, preview opt-ins, and resource ownership. */
public final class FoundryEvaluationClientOptions {
    private final URI projectEndpoint;
    private final AzureAuthenticationProvider authenticationProvider;
    private final Duration requestTimeout;
    private final Duration operationTimeout;
    private final Duration initialPollDelay;
    private final Duration maxPollDelay;
    private final int maxRetries;
    private final int maxResponseBytes;
    private final int maxPageSize;
    private final int maxPages;
    private final boolean previewEvaluatorManagement;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;

    private FoundryEvaluationClientOptions(Builder builder) {
        projectEndpoint = validateEndpoint(builder.projectEndpoint);
        authenticationProvider = Objects.requireNonNull(builder.authenticationProvider, "authenticationProvider");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        operationTimeout = positive(builder.operationTimeout, "operationTimeout");
        initialPollDelay = positive(builder.initialPollDelay, "initialPollDelay");
        maxPollDelay = positive(builder.maxPollDelay, "maxPollDelay");
        if (maxPollDelay.compareTo(initialPollDelay) < 0) {
            throw new IllegalArgumentException("maxPollDelay must not be less than initialPollDelay.");
        }
        if (builder.maxRetries < 0 || builder.maxRetries > 10) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 10.");
        }
        maxRetries = builder.maxRetries;
        maxResponseBytes = bounded(builder.maxResponseBytes, 1, 64 * 1024 * 1024, "maxResponseBytes");
        maxPageSize = bounded(builder.maxPageSize, 1, 1000, "maxPageSize");
        maxPages = bounded(builder.maxPages, 1, 1000, "maxPages");
        previewEvaluatorManagement = builder.previewEvaluatorManagement;
        executor = builder.executor;
        scheduler = builder.scheduler;
    }

    /** Creates an options builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the validated Foundry project endpoint. */
    public URI projectEndpoint() {
        return projectEndpoint;
    }

    /** Returns the caller-owned authentication provider. */
    public AzureAuthenticationProvider authenticationProvider() {
        return authenticationProvider;
    }

    /** Returns the per-request timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns the total polling timeout. */
    public Duration operationTimeout() {
        return operationTimeout;
    }

    /** Returns the initial polling delay. */
    public Duration initialPollDelay() {
        return initialPollDelay;
    }

    /** Returns the maximum polling delay. */
    public Duration maxPollDelay() {
        return maxPollDelay;
    }

    /** Returns the bounded retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the maximum response bytes. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the maximum page size. */
    public int maxPageSize() {
        return maxPageSize;
    }

    /** Returns the maximum pages collected by terminal result helpers. */
    public int maxPages() {
        return maxPages;
    }

    /** Returns whether preview evaluator management was explicitly enabled. */
    public boolean previewEvaluatorManagement() {
        return previewEvaluatorManagement;
    }

    /** Returns the optional caller-owned HTTP executor. */
    public Executor executor() {
        return executor;
    }

    /** Returns the optional caller-owned scheduler. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    private static URI validateEndpoint(URI value) {
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
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || project == null
                || project.isBlank()
                || project.contains("/")) {
            throw new IllegalArgumentException(
                    "projectEndpoint must be an HTTPS URI ending in /api/projects/<project>.");
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

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + ".");
        }
        return value;
    }

    /** Builds immutable Foundry evaluation options. */
    public static final class Builder {
        private URI projectEndpoint;
        private AzureAuthenticationProvider authenticationProvider;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration operationTimeout = Duration.ofMinutes(5);
        private Duration initialPollDelay = Duration.ofMillis(500);
        private Duration maxPollDelay = Duration.ofSeconds(5);
        private int maxRetries = 2;
        private int maxResponseBytes = 8 * 1024 * 1024;
        private int maxPageSize = 100;
        private int maxPages = 100;
        private boolean previewEvaluatorManagement;
        private Executor executor;
        private ScheduledExecutorService scheduler;

        private Builder() {}

        /** Sets the Foundry project endpoint. */
        public Builder projectEndpoint(URI value) {
            projectEndpoint = value;
            return this;
        }

        /** Sets the Foundry project endpoint. */
        public Builder projectEndpoint(String value) {
            return projectEndpoint(URI.create(Objects.requireNonNull(value, "value")));
        }

        /** Sets the caller-owned authentication provider. */
        public Builder authenticationProvider(AzureAuthenticationProvider value) {
            authenticationProvider = value;
            return this;
        }

        /** Sets the per-request timeout. */
        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        /** Sets the total operation timeout. */
        public Builder operationTimeout(Duration value) {
            operationTimeout = value;
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

        /** Sets the bounded retry count. */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /** Sets the maximum response bytes. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets the maximum page size. */
        public Builder maxPageSize(int value) {
            maxPageSize = value;
            return this;
        }

        /** Sets the maximum pages collected by terminal helpers. */
        public Builder maxPages(int value) {
            maxPages = value;
            return this;
        }

        /** Explicitly enables preview evaluator-management operations. */
        public Builder previewEvaluatorManagement(boolean value) {
            previewEvaluatorManagement = value;
            return this;
        }

        /** Sets a caller-owned HTTP executor. */
        public Builder executor(Executor value) {
            executor = value;
            return this;
        }

        /** Sets a caller-owned scheduler. */
        public Builder scheduler(ScheduledExecutorService value) {
            scheduler = value;
            return this;
        }

        /** Creates immutable options. */
        public FoundryEvaluationClientOptions build() {
            return new FoundryEvaluationClientOptions(this);
        }
    }
}
