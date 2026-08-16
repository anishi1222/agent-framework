// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Defines immutable, redirect-free Copilot Studio Direct-to-Engine client configuration.
 */
public final class CopilotStudioClientOptions {
    private static final Pattern GUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern BOT = Pattern.compile("[A-Za-z0-9_.-]{1,256}");

    private final String tenantId;

    private final String environmentId;

    private final String botIdentifier;

    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final boolean allowInsecureLoopback;

    private final String locale;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final Duration reconnectTimeout;

    private final Duration tokenRefreshSkew;

    private final Duration minimumBackoff;

    private final Duration maximumBackoff;

    private final CopilotStudioLimits limits;

    private CopilotStudioClientOptions(Builder builder) {
        tenantId = guid(builder.tenantId, "tenantId");
        environmentId = builder.environmentId == null ? null : guid(builder.environmentId, "environmentId");
        botIdentifier = builder.botIdentifier == null ? null : bot(builder.botIdentifier);
        endpoint = normalizeEndpoint(resolveEndpoint(builder));
        allowedHosts = hosts(builder.allowedHosts);
        allowInsecureLoopback = builder.allowInsecureLoopback;
        locale = optional(builder.locale);
        connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        reconnectTimeout = positive(builder.reconnectTimeout, "reconnectTimeout");
        tokenRefreshSkew = positive(builder.tokenRefreshSkew, "tokenRefreshSkew");
        minimumBackoff = positive(builder.minimumBackoff, "minimumBackoff");
        maximumBackoff = positive(builder.maximumBackoff, "maximumBackoff");
        limits = Objects.requireNonNull(builder.limits, "limits");
        validateEndpoint();
        if (minimumBackoff.compareTo(maximumBackoff) > 0) {
            throw new IllegalArgumentException("minimumBackoff must not exceed maximumBackoff.");
        }
    }

    /**
     * Creates an options builder.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the validated tenant identity. */
    public String tenantId() {
        return tenantId;
    }

    /** Returns the optional validated Power Platform environment identity. */
    public String environmentId() {
        return environmentId;
    }

    /** Returns the optional validated bot schema identifier. */
    public String botIdentifier() {
        return botIdentifier;
    }

    /** Returns the normalized bot endpoint without a conversation suffix or query. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns the explicit remote-host allowlist. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns whether unencrypted loopback is permitted. */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    /** Returns the optional conversation locale. */
    public String locale() {
        return locale;
    }

    /** Returns the TCP connect timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the HTTP request timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns the reconnect timeout. */
    public Duration reconnectTimeout() {
        return reconnectTimeout;
    }

    /** Returns the proactive token-refresh skew. */
    public Duration tokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    /** Returns the minimum reconnect backoff. */
    public Duration minimumBackoff() {
        return minimumBackoff;
    }

    /** Returns the maximum reconnect backoff. */
    public Duration maximumBackoff() {
        return maximumBackoff;
    }

    /** Returns protocol limits. */
    public CopilotStudioLimits limits() {
        return limits;
    }

    /**
     * Returns the Power Platform token audience derived from the endpoint cloud.
     *
     * @return audience ending in {@code /.default}
     */
    public String tokenAudience() {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String suffix;
        if (host.endsWith("powerplatform.microsoft.us")) {
            suffix = host.contains(".high.")
                    ? "api.high.powerplatform.microsoft.us"
                    : "api.gov.powerplatform.microsoft.us";
        } else if (host.endsWith("appsplatform.us")) {
            suffix = "api.appsplatform.us";
        } else if (host.endsWith("powerplatform.partner.microsoftonline.cn")) {
            suffix = "api.powerplatform.partner.microsoftonline.cn";
        } else {
            suffix = "api.powerplatform.com";
        }
        return "https://" + suffix + "/.default";
    }

    private void validateEndpoint() {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        boolean loopback = isLoopback(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback && allowInsecureLoopback)) {
            throw new IllegalArgumentException("endpoint must use HTTPS; HTTP requires explicit loopback opt-in.");
        }
        if (!loopback && !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("endpoint host must be present in allowedHosts.");
        }
        String path = endpoint.getPath();
        if (path == null || !path.contains("/copilotstudio/") || !path.contains("/bots/")) {
            throw new IllegalArgumentException("endpoint must identify a Copilot Studio Direct-to-Engine bot.");
        }
    }

    private static URI resolveEndpoint(Builder builder) {
        if (builder.endpoint != null) {
            if (builder.environmentId != null || builder.botIdentifier != null) {
                throw new IllegalArgumentException(
                        "direct endpoint cannot be combined with environmentId or botIdentifier.");
            }
            return builder.endpoint;
        }
        String environmentId = guid(builder.environmentId, "environmentId");
        String botIdentifier = bot(builder.botIdentifier);
        String normalized = environmentId.replace("-", "").toLowerCase(Locale.ROOT);
        String host = normalized.substring(0, normalized.length() - 2)
                + "."
                + normalized.substring(normalized.length() - 2)
                + ".environment.api.powerplatform.com";
        return URI.create("https://" + host + "/copilotstudio/dataverse-backed/authenticated/bots/" + botIdentifier);
    }

    private static URI normalizeEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute host URI without user info or fragment.");
        }
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        int conversations = path.indexOf("/conversations");
        if (conversations >= 0) {
            path = path.substring(0, conversations);
        }
        while (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("endpoint cannot be normalized.", exception);
        }
    }

    private static Set<String> hosts(Set<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("allowedHosts must contain non-blank values.");
            }
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static String guid(String value, String name) {
        if (value == null || !GUID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a canonical GUID.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String bot(String value) {
        if (value == null || !BOT.matcher(value).matches()) {
            throw new IllegalArgumentException("botIdentifier contains unsupported characters.");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    /** Builds immutable {@link CopilotStudioClientOptions} instances. */
    public static final class Builder {
        private String tenantId;

        private String environmentId;

        private String botIdentifier;

        private URI endpoint;

        private Set<String> allowedHosts = Set.of();

        private boolean allowInsecureLoopback;

        private String locale;

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofSeconds(60);

        private Duration reconnectTimeout = Duration.ofMinutes(2);

        private Duration tokenRefreshSkew = Duration.ofMinutes(2);

        private Duration minimumBackoff = Duration.ofMillis(250);

        private Duration maximumBackoff = Duration.ofSeconds(5);

        private CopilotStudioLimits limits = CopilotStudioLimits.defaults();

        private Builder() {}

        /** Sets the Entra tenant identity. */
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /** Selects a standard Power Platform environment and published bot. */
        public Builder environment(String environmentId, String botIdentifier) {
            this.environmentId = environmentId;
            this.botIdentifier = botIdentifier;
            return this;
        }

        /** Sets an exact DirectConnect bot endpoint. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        /** Replaces the remote-host allowlist. */
        public Builder allowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = Set.copyOf(Objects.requireNonNull(allowedHosts, "allowedHosts"));
            return this;
        }

        /** Explicitly permits unencrypted loopback endpoints. */
        public Builder allowInsecureLoopback(boolean allowInsecureLoopback) {
            this.allowInsecureLoopback = allowInsecureLoopback;
            return this;
        }

        /** Sets the optional locale. */
        public Builder locale(String locale) {
            this.locale = locale;
            return this;
        }

        /** Sets the TCP connect timeout. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /** Sets the HTTP request timeout. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /** Sets the total reconnect timeout. */
        public Builder reconnectTimeout(Duration reconnectTimeout) {
            this.reconnectTimeout = Objects.requireNonNull(reconnectTimeout, "reconnectTimeout");
            return this;
        }

        /** Sets proactive token-refresh skew. */
        public Builder tokenRefreshSkew(Duration tokenRefreshSkew) {
            this.tokenRefreshSkew = Objects.requireNonNull(tokenRefreshSkew, "tokenRefreshSkew");
            return this;
        }

        /** Sets minimum reconnect backoff. */
        public Builder minimumBackoff(Duration minimumBackoff) {
            this.minimumBackoff = Objects.requireNonNull(minimumBackoff, "minimumBackoff");
            return this;
        }

        /** Sets maximum reconnect backoff. */
        public Builder maximumBackoff(Duration maximumBackoff) {
            this.maximumBackoff = Objects.requireNonNull(maximumBackoff, "maximumBackoff");
            return this;
        }

        /** Sets protocol limits. */
        public Builder limits(CopilotStudioLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Creates immutable client options.
         *
         * @return client options
         */
        public CopilotStudioClientOptions build() {
            return new CopilotStudioClientOptions(this);
        }
    }
}
