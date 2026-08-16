// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Configures the secure redirect-free JDK AG-UI HTTP/SSE client. */
public final class AGUIClientOptions {
    private static final Set<String> RESERVED_HEADERS =
            Set.of("accept", "connection", "content-length", "content-type", "host", "proxy-authorization");

    private final URI endpoint;

    private final URI capabilitiesEndpoint;

    private final AGUILimits limits;

    private final Duration connectTimeout;

    private final Duration requestTimeout;

    private final Duration idleTimeout;

    private final Set<String> allowedHosts;

    private final Map<String, String> headers;

    private AGUIClientOptions(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint, builder.allowInsecureLoopback);
        capabilitiesEndpoint = builder.capabilitiesEndpoint == null
                ? appendPath(endpoint, "capabilities")
                : validateEndpoint(builder.capabilitiesEndpoint, builder.allowInsecureLoopback);
        limits = java.util.Objects.requireNonNull(builder.limits, "limits");
        connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        idleTimeout = positive(builder.idleTimeout, "idleTimeout");
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        builder.allowedHosts.forEach(host -> hosts.add(normalizeHost(host)));
        hosts.add(normalizeHost(endpoint.getHost()));
        allowedHosts = Collections.unmodifiableSet(hosts);
        if (!allowedHosts.contains(normalizeHost(endpoint.getHost()))
                || !allowedHosts.contains(normalizeHost(capabilitiesEndpoint.getHost()))) {
            throw new IllegalArgumentException("Endpoint host is not allowed.");
        }
        LinkedHashMap<String, String> copiedHeaders = new LinkedHashMap<>();
        builder.headers.forEach((name, value) -> {
            String normalized = validateHeaderName(name);
            if (RESERVED_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException("Header is controlled by AGUIClient.");
            }
            copiedHeaders.put(name, validateHeaderValue(value));
        });
        headers = Collections.unmodifiableMap(copiedHeaders);
    }

    /**
     * Creates an options builder for an endpoint.
     *
     * @param endpoint AG-UI POST endpoint
     * @return builder
     */
    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /**
     * Returns the AG-UI POST endpoint.
     *
     * @return endpoint
     */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * Returns the framework-extension capabilities endpoint.
     *
     * @return capabilities endpoint
     */
    public URI capabilitiesEndpoint() {
        return capabilitiesEndpoint;
    }

    /**
     * Returns mandatory processing limits.
     *
     * @return limits
     */
    public AGUILimits limits() {
        return limits;
    }

    /**
     * Returns the connection timeout.
     *
     * @return timeout
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * Returns the complete run timeout.
     *
     * @return timeout
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * Returns the maximum interval without received SSE bytes.
     *
     * @return timeout
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /**
     * Returns the exact lower-case host allowlist.
     *
     * @return hosts
     */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Returns immutable additional request headers.
     *
     * <p>Callers must not log this map because it can contain credentials.
     *
     * @return headers
     */
    public Map<String, String> headers() {
        return headers;
    }

    /** Builds immutable {@link AGUIClientOptions}. */
    public static final class Builder {
        private final URI endpoint;

        private URI capabilitiesEndpoint;

        private AGUILimits limits = AGUILimits.defaults();

        private Duration connectTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofMinutes(5);

        private Duration idleTimeout = Duration.ofSeconds(30);

        private boolean allowInsecureLoopback;

        private final Set<String> allowedHosts = new LinkedHashSet<>();

        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder(URI endpoint) {
            this.endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        }

        /**
         * Sets an explicit framework-extension capabilities endpoint.
         *
         * @param value endpoint
         * @return this builder
         */
        public Builder capabilitiesEndpoint(URI value) {
            capabilitiesEndpoint = java.util.Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets mandatory codec and publisher limits.
         *
         * @param value limits
         * @return this builder
         */
        public Builder limits(AGUILimits value) {
            limits = java.util.Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the positive connection timeout.
         *
         * @param value timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            connectTimeout = positive(value, "connectTimeout");
            return this;
        }

        /**
         * Sets the positive complete run timeout.
         *
         * @param value timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration value) {
            requestTimeout = positive(value, "requestTimeout");
            return this;
        }

        /**
         * Sets the positive interval without SSE bytes.
         *
         * @param value timeout
         * @return this builder
         */
        public Builder idleTimeout(Duration value) {
            idleTimeout = positive(value, "idleTimeout");
            return this;
        }

        /**
         * Allows plain HTTP only when the endpoint host resolves entirely to loopback addresses.
         *
         * @return this builder
         */
        public Builder allowInsecureLoopback() {
            allowInsecureLoopback = true;
            return this;
        }

        /**
         * Adds an exact allowed endpoint host.
         *
         * @param value DNS name or IP literal without port
         * @return this builder
         */
        public Builder allowedHost(String value) {
            allowedHosts.add(normalizeHost(value));
            return this;
        }

        /**
         * Adds one non-reserved request header.
         *
         * @param name header name
         * @param value header value, which may contain a credential
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.put(
                    java.util.Objects.requireNonNull(name, "name"), java.util.Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public AGUIClientOptions build() {
            return new AGUIClientOptions(this);
        }
    }

    private static URI validateEndpoint(URI value, boolean allowInsecureLoopback) {
        java.util.Objects.requireNonNull(value, "endpoint");
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || value.getRawPath() == null
                || value.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("AG-UI endpoint must be an absolute hierarchical URI without secrets.");
        }
        if ("https".equalsIgnoreCase(value.getScheme())) {
            return value;
        }
        if (!"http".equalsIgnoreCase(value.getScheme()) || !allowInsecureLoopback || !resolvesOnlyToLoopback(value)) {
            throw new IllegalArgumentException(
                    "AG-UI endpoint requires HTTPS unless loopback HTTP is explicitly enabled.");
        }
        return value;
    }

    private static boolean resolvesOnlyToLoopback(URI value) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(value.getHost());
            return addresses.length > 0 && java.util.Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static URI appendPath(URI endpoint, String segment) {
        String path =
                endpoint.getPath().endsWith("/") ? endpoint.getPath() + segment : endpoint.getPath() + "/" + segment;
        return URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
    }

    private static String normalizeHost(String value) {
        String host = AGUIValidation.nonBlank(value, "host").toLowerCase(Locale.ROOT);
        if (host.contains("/")
                || host.contains("@")
                || host.contains("?")
                || host.contains("#")
                || host.contains(":") && !(host.startsWith("[") && host.endsWith("]"))) {
            throw new IllegalArgumentException("Allowed host must not contain a port or URI syntax.");
        }
        return host;
    }

    private static Duration positive(Duration value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static String validateHeaderName(String value) {
        String name = AGUIValidation.nonBlank(value, "header name");
        if (!name.chars()
                .allMatch(character ->
                        Character.isLetterOrDigit(character) || "!#$%&'*+-.^_`|~".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("Header name contains invalid characters.");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String validateHeaderValue(String value) {
        java.util.Objects.requireNonNull(value, "header value");
        if (value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("Header value contains a line break.");
        }
        return value;
    }
}
