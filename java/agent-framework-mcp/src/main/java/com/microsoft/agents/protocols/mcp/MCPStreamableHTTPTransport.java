// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.ValidationException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configures an MCP Streamable HTTP endpoint with restrictive network defaults.
 *
 * <p>HTTPS is required unless {@link Builder#allowInsecureLoopback(boolean)} explicitly permits an
 * HTTP loopback endpoint. Redirects are always disabled, and the endpoint host must appear in the
 * configured allowlist.
 */
public final class MCPStreamableHTTPTransport implements MCPTransport {
    private final URI endpoint;

    private final Set<String> allowedHosts;

    private final Map<String, String> headers;

    private final Duration connectTimeout;

    private final boolean allowInsecureLoopback;

    private MCPStreamableHTTPTransport(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint, builder.allowInsecureLoopback);
        allowedHosts = normalizeHosts(builder.allowedHosts);
        String endpointHost = normalizeHost(endpoint.getHost());
        if (!allowedHosts.contains(endpointHost)) {
            throw new ValidationException("endpoint host must appear in allowedHosts.");
        }
        headers = validateHeaders(builder.headers);
        connectTimeout = MCPValidation.positive(builder.connectTimeout, "connectTimeout");
        allowInsecureLoopback = builder.allowInsecureLoopback;
    }

    /**
     * Creates a builder for a complete Streamable HTTP endpoint URI.
     *
     * @param endpoint absolute endpoint such as {@code https://example.test/mcp}
     * @return transport builder
     */
    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /**
     * Returns the complete MCP endpoint.
     *
     * @return endpoint URI
     */
    public URI endpoint() {
        return endpoint;
    }

    /**
     * Returns normalized allowed DNS host names.
     *
     * @return immutable host allowlist
     */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Returns immutable request headers.
     *
     * <p>Values may contain credentials and are never included by {@link #toString()}.
     *
     * @return request headers
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Returns the HTTP connection timeout.
     *
     * @return positive timeout
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * Reports whether plain HTTP is allowed for a loopback endpoint.
     *
     * @return {@code true} only for explicit development opt-in
     */
    public boolean allowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    @Override
    public String toString() {
        return "MCPStreamableHTTPTransport[endpoint="
                + endpoint
                + ", allowedHosts="
                + allowedHosts
                + ", headerNames="
                + headers.keySet()
                + ", connectTimeout="
                + connectTimeout
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + "]";
    }

    /** Builds a secure Streamable HTTP transport configuration. */
    public static final class Builder {
        private final URI endpoint;

        private Set<String> allowedHosts;

        private final Map<String, String> headers = new LinkedHashMap<>();

        private Duration connectTimeout = Duration.ofSeconds(10);

        private boolean allowInsecureLoopback;

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            allowedHosts = endpoint.getHost() == null ? Set.of() : Set.of(endpoint.getHost());
        }

        /**
         * Replaces the remote host allowlist.
         *
         * @param hosts DNS names or literal loopback addresses
         * @return this builder
         */
        public Builder allowedHosts(Set<String> hosts) {
            allowedHosts = Objects.requireNonNull(hosts, "hosts");
            return this;
        }

        /**
         * Replaces static request headers.
         *
         * @param headers header values, including optional authorization
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.clear();
            this.headers.putAll(Objects.requireNonNull(headers, "headers"));
            return this;
        }

        /**
         * Adds one static request header.
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * Sets the HTTP connection timeout.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder connectTimeout(Duration timeout) {
            connectTimeout = timeout;
            return this;
        }

        /**
         * Allows plain HTTP only when the endpoint resolves syntactically to loopback.
         *
         * @param allowed explicit development opt-in
         * @return this builder
         */
        public Builder allowInsecureLoopback(boolean allowed) {
            allowInsecureLoopback = allowed;
            return this;
        }

        /**
         * Creates the immutable configuration.
         *
         * @return validated Streamable HTTP transport
         */
        public MCPStreamableHTTPTransport build() {
            return new MCPStreamableHTTPTransport(this);
        }
    }

    private static URI validateEndpoint(URI endpoint, boolean allowInsecureLoopback) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || endpoint.getQuery() != null) {
            throw new ValidationException(
                    "endpoint must be an absolute HTTP(S) URI without user info, query, or fragment.");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = normalizeHost(endpoint.getHost());
        if (!"https".equals(scheme) && !("http".equals(scheme) && allowInsecureLoopback && isLoopbackHost(host))) {
            throw new ValidationException("endpoint must use HTTPS; HTTP is permitted only for explicit loopback use.");
        }
        String path = endpoint.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path) || path.contains("..")) {
            throw new ValidationException("endpoint must include a non-traversing MCP path.");
        }
        try {
            return new URI(
                    scheme, null, host, endpoint.getPort(), path.startsWith("/") ? path : "/" + path, null, null);
        } catch (URISyntaxException exception) {
            throw new ValidationException("endpoint could not be normalized.", exception);
        }
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new ValidationException("allowedHosts must not be empty.");
        }
        return hosts.stream()
                .map(MCPStreamableHTTPTransport::normalizeHost)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeHost(String host) {
        String value = MCPValidation.nonBlank(host, "host").toLowerCase(Locale.ROOT);
        if ("[::1]".equals(value)) {
            return "::1";
        }
        try {
            return IDN.toASCII(value);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("host is invalid.", exception);
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static Map<String, String> validateHeaders(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String safeName = MCPValidation.nonBlank(name, "header name");
            String safeValue = Objects.requireNonNull(value, "header value");
            if (!safeName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || safeValue.indexOf('\r') >= 0
                    || safeValue.indexOf('\n') >= 0) {
                throw new ValidationException("HTTP header contains invalid characters.");
            }
            if (Set.of("host", "content-length", "connection").contains(safeName.toLowerCase(Locale.ROOT))) {
                throw new ValidationException("HTTP header '" + safeName + "' is transport-owned.");
            }
            result.put(safeName, safeValue);
        });
        return Map.copyOf(result);
    }
}
