// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import com.microsoft.agents.azure.AzureAuthenticationProvider;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Defines immutable Microsoft Purview Graph, policy, cache, and background-work settings. */
public final class PurviewSettings {
    private final AzureAuthenticationProvider authenticationProvider;
    private final URI graphBaseUri;
    private final String appName;
    private final String appVersion;
    private final String tenantId;
    private final PurviewAppLocation appLocation;
    private final PurviewFailureMode failureMode;
    private final PurviewFailureMode paymentRequiredMode;
    private final String blockedPromptMessage;
    private final String blockedResponseMessage;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final Duration cacheTimeToLive;
    private final int maximumCacheEntries;
    private final int maximumPendingJobs;
    private final int maximumConcurrentJobs;
    private final ExecutorService backgroundExecutor;
    private final ScheduledExecutorService scheduler;
    private final PurviewTelemetryListener telemetryListener;

    private PurviewSettings(Builder builder) {
        authenticationProvider = Objects.requireNonNull(builder.authenticationProvider, "authenticationProvider");
        graphBaseUri = validateGraphUri(builder.graphBaseUri);
        appName = required(builder.appName, "appName");
        appVersion = required(builder.appVersion, "appVersion");
        tenantId = optional(builder.tenantId, "tenantId");
        appLocation = builder.appLocation;
        failureMode = Objects.requireNonNull(builder.failureMode, "failureMode");
        paymentRequiredMode = Objects.requireNonNull(builder.paymentRequiredMode, "paymentRequiredMode");
        blockedPromptMessage = required(builder.blockedPromptMessage, "blockedPromptMessage");
        blockedResponseMessage = required(builder.blockedResponseMessage, "blockedResponseMessage");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        maxRetries = bounded(builder.maxRetries, 0, 10, "maxRetries");
        maxRequestBytes = bounded(builder.maxRequestBytes, 1, 64 * 1024 * 1024, "maxRequestBytes");
        maxResponseBytes = bounded(builder.maxResponseBytes, 1, 64 * 1024 * 1024, "maxResponseBytes");
        cacheTimeToLive = positive(builder.cacheTimeToLive, "cacheTimeToLive");
        maximumCacheEntries = bounded(builder.maximumCacheEntries, 1, 1_000_000, "maximumCacheEntries");
        maximumPendingJobs = bounded(builder.maximumPendingJobs, 1, 100_000, "maximumPendingJobs");
        maximumConcurrentJobs = bounded(builder.maximumConcurrentJobs, 1, 10_000, "maximumConcurrentJobs");
        if (maximumConcurrentJobs > maximumPendingJobs) {
            throw new IllegalArgumentException("maximumConcurrentJobs must not exceed maximumPendingJobs.");
        }
        backgroundExecutor = builder.backgroundExecutor;
        scheduler = builder.scheduler;
        telemetryListener = Objects.requireNonNull(builder.telemetryListener, "telemetryListener");
    }

    /** Creates a settings builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the caller-owned authentication provider. */
    public AzureAuthenticationProvider authenticationProvider() {
        return authenticationProvider;
    }

    /** Returns the verified Microsoft Graph v1.0 base URI. */
    public URI graphBaseUri() {
        return graphBaseUri;
    }

    /** Returns the public application name. */
    public String appName() {
        return appName;
    }

    /** Returns the application version. */
    public String appVersion() {
        return appVersion;
    }

    /** Returns the optional tenant override. */
    public String tenantId() {
        return tenantId;
    }

    /** Returns the optional configured application location. */
    public PurviewAppLocation appLocation() {
        return appLocation;
    }

    /** Returns the general Purview dependency failure mode. */
    public PurviewFailureMode failureMode() {
        return failureMode;
    }

    /** Returns the payment-required failure mode. */
    public PurviewFailureMode paymentRequiredMode() {
        return paymentRequiredMode;
    }

    /** Returns the blocked prompt text. */
    public String blockedPromptMessage() {
        return blockedPromptMessage;
    }

    /** Returns the blocked response text. */
    public String blockedResponseMessage() {
        return blockedResponseMessage;
    }

    /** Returns the request timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns the bounded retry count. */
    public int maxRetries() {
        return maxRetries;
    }

    /** Returns the maximum encoded request size. */
    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** Returns the maximum response size. */
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    /** Returns the protection-scope cache TTL. */
    public Duration cacheTimeToLive() {
        return cacheTimeToLive;
    }

    /** Returns the maximum protection-scope cache entries. */
    public int maximumCacheEntries() {
        return maximumCacheEntries;
    }

    /** Returns the maximum admitted background jobs. */
    public int maximumPendingJobs() {
        return maximumPendingJobs;
    }

    /** Returns the maximum concurrent background jobs. */
    public int maximumConcurrentJobs() {
        return maximumConcurrentJobs;
    }

    /** Returns the optional caller-owned background executor. */
    public ExecutorService backgroundExecutor() {
        return backgroundExecutor;
    }

    /** Returns the optional caller-owned retry scheduler. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** Returns the privacy-safe telemetry listener. */
    public PurviewTelemetryListener telemetryListener() {
        return telemetryListener;
    }

    private static URI validateGraphUri(URI value) {
        Objects.requireNonNull(value, "graphBaseUri");
        String path = value.getPath() == null ? "" : value.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String host = value.getHost();
        if (!value.isAbsolute()
                || !"https".equalsIgnoreCase(value.getScheme())
                || host == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || !"/v1.0".equals(path)
                || !isGraphHost(host)) {
            throw new IllegalArgumentException("graphBaseUri must be a verified Microsoft Graph HTTPS v1.0 endpoint.");
        }
        return URI.create(value.toString().replaceAll("/+$", ""));
    }

    private static boolean isGraphHost(String host) {
        return switch (host.toLowerCase(Locale.ROOT)) {
            case "graph.microsoft.com", "graph.microsoft.us", "microsoftgraph.chinacloudapi.cn" -> true;
            default -> false;
        };
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String optional(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
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

    /** Builds immutable Purview settings. */
    public static final class Builder {
        private AzureAuthenticationProvider authenticationProvider;
        private URI graphBaseUri = URI.create("https://graph.microsoft.com/v1.0");
        private String appName;
        private String appVersion = "Unknown";
        private String tenantId;
        private PurviewAppLocation appLocation;
        private PurviewFailureMode failureMode = PurviewFailureMode.FAIL_CLOSED;
        private PurviewFailureMode paymentRequiredMode = PurviewFailureMode.FAIL_CLOSED;
        private String blockedPromptMessage = "Prompt blocked by policy";
        private String blockedResponseMessage = "Response blocked by policy";
        private Duration requestTimeout = Duration.ofSeconds(10);
        private int maxRetries = 2;
        private int maxRequestBytes = 4 * 1024 * 1024;
        private int maxResponseBytes = 4 * 1024 * 1024;
        private Duration cacheTimeToLive = Duration.ofMinutes(30);
        private int maximumCacheEntries = 10_000;
        private int maximumPendingJobs = 100;
        private int maximumConcurrentJobs = 10;
        private ExecutorService backgroundExecutor;
        private ScheduledExecutorService scheduler;
        private PurviewTelemetryListener telemetryListener = PurviewTelemetryListener.none();

        private Builder() {}

        /** Sets the caller-owned authentication provider. */
        public Builder authenticationProvider(AzureAuthenticationProvider value) {
            authenticationProvider = value;
            return this;
        }

        /** Sets a verified Microsoft Graph v1.0 base URI. */
        public Builder graphBaseUri(URI value) {
            graphBaseUri = value;
            return this;
        }

        /** Sets the public application name. */
        public Builder appName(String value) {
            appName = value;
            return this;
        }

        /** Sets the application version. */
        public Builder appVersion(String value) {
            appVersion = value;
            return this;
        }

        /** Sets an optional tenant override. */
        public Builder tenantId(String value) {
            tenantId = value;
            return this;
        }

        /** Sets an explicit protected application location. */
        public Builder appLocation(PurviewAppLocation value) {
            appLocation = value;
            return this;
        }

        /** Sets general dependency failure behavior. */
        public Builder failureMode(PurviewFailureMode value) {
            failureMode = value;
            return this;
        }

        /** Sets payment-required failure behavior. */
        public Builder paymentRequiredMode(PurviewFailureMode value) {
            paymentRequiredMode = value;
            return this;
        }

        /** Sets blocked prompt text. */
        public Builder blockedPromptMessage(String value) {
            blockedPromptMessage = value;
            return this;
        }

        /** Sets blocked response text. */
        public Builder blockedResponseMessage(String value) {
            blockedResponseMessage = value;
            return this;
        }

        /** Sets the request timeout. */
        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        /** Sets the bounded retry count. */
        public Builder maxRetries(int value) {
            maxRetries = value;
            return this;
        }

        /** Sets maximum request bytes. */
        public Builder maxRequestBytes(int value) {
            maxRequestBytes = value;
            return this;
        }

        /** Sets maximum response bytes. */
        public Builder maxResponseBytes(int value) {
            maxResponseBytes = value;
            return this;
        }

        /** Sets protection-scope cache TTL. */
        public Builder cacheTimeToLive(Duration value) {
            cacheTimeToLive = value;
            return this;
        }

        /** Sets maximum protection-scope cache entries. */
        public Builder maximumCacheEntries(int value) {
            maximumCacheEntries = value;
            return this;
        }

        /** Sets maximum admitted background jobs. */
        public Builder maximumPendingJobs(int value) {
            maximumPendingJobs = value;
            return this;
        }

        /** Sets maximum concurrent background jobs. */
        public Builder maximumConcurrentJobs(int value) {
            maximumConcurrentJobs = value;
            return this;
        }

        /** Sets a caller-owned background executor. */
        public Builder backgroundExecutor(ExecutorService value) {
            backgroundExecutor = value;
            return this;
        }

        /** Sets a caller-owned retry scheduler. */
        public Builder scheduler(ScheduledExecutorService value) {
            scheduler = value;
            return this;
        }

        /** Sets a privacy-safe telemetry listener. */
        public Builder telemetryListener(PurviewTelemetryListener value) {
            telemetryListener = value;
            return this;
        }

        /** Creates immutable settings. */
        public PurviewSettings build() {
            return new PurviewSettings(this);
        }
    }
}
