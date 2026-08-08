// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.mcp.MCPLimits;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configures the embedded Streamable HTTP/SSE host.
 *
 * <p>The default binds only to loopback. Binding to a non-loopback address requires an explicit
 * declaration that a trusted TLS reverse proxy protects the listener. The built-in host does not
 * terminate TLS directly.
 */
public final class MCPStreamableHTTPServerOptions {
    private final InetAddress bindAddress;

    private final int port;

    private final String endpoint;

    private final Set<String> allowedHosts;

    private final Set<String> allowedOrigins;

    private final Duration keepAliveInterval;

    private final boolean behindTrustedTLSProxy;

    private final MCPLimits limits;

    private final int maxSessions;

    private MCPStreamableHTTPServerOptions(Builder builder) {
        bindAddress = Objects.requireNonNull(builder.bindAddress, "bindAddress");
        port = HostingMCPValidation.port(builder.port);
        endpoint = validateEndpoint(builder.endpoint);
        allowedHosts = copyNonBlank(builder.allowedHosts, "allowedHosts");
        if (allowedHosts.isEmpty()) {
            throw new ValidationException("allowedHosts must not be empty.");
        }
        allowedOrigins = copyNonBlank(builder.allowedOrigins, "allowedOrigins");
        keepAliveInterval = builder.keepAliveInterval == null
                ? null
                : HostingMCPValidation.positive(builder.keepAliveInterval, "keepAliveInterval");
        behindTrustedTLSProxy = builder.behindTrustedTLSProxy;
        if (!bindAddress.isLoopbackAddress() && !behindTrustedTLSProxy) {
            throw new ValidationException("non-loopback MCP HTTP binding requires behindTrustedTLSProxy=true.");
        }
        if (!bindAddress.isLoopbackAddress() && allowedOrigins.isEmpty()) {
            throw new ValidationException("non-loopback MCP HTTP binding requires an explicit Origin allowlist.");
        }
        limits = Objects.requireNonNull(builder.limits, "limits");
        maxSessions = HostingMCPValidation.positive(builder.maxSessions, "maxSessions");
    }

    /**
     * Creates options with a loopback-only listener and an ephemeral port.
     *
     * @return options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the listener address.
     *
     * @return bind address
     */
    public InetAddress bindAddress() {
        return bindAddress;
    }

    /**
     * Returns the requested port, where zero requests an ephemeral port.
     *
     * @return port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the MCP endpoint path.
     *
     * @return endpoint
     */
    public String endpoint() {
        return endpoint;
    }

    /**
     * Returns exact or wildcard-port Host header patterns.
     *
     * @return immutable Host allowlist
     */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Returns exact or wildcard-port Origin header patterns.
     *
     * @return immutable Origin allowlist
     */
    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Returns the optional keep-alive interval.
     *
     * @return interval, or {@code null}
     */
    public Duration keepAliveInterval() {
        return keepAliveInterval;
    }

    /**
     * Reports whether a trusted TLS proxy is declared for a non-loopback bind.
     *
     * @return proxy declaration
     */
    public boolean behindTrustedTLSProxy() {
        return behindTrustedTLSProxy;
    }

    /**
     * Returns finite request and concurrency limits.
     *
     * @return limits
     */
    public MCPLimits limits() {
        return limits;
    }

    /**
     * Returns the maximum simultaneously retained MCP sessions.
     *
     * @return positive session bound
     */
    public int maxSessions() {
        return maxSessions;
    }

    /** Builds Streamable HTTP server options. */
    public static final class Builder {
        private InetAddress bindAddress = loopback();

        private int port;

        private String endpoint = "/mcp";

        private final Set<String> allowedHosts = new LinkedHashSet<>(List.of("localhost:*", "127.0.0.1:*", "[::1]:*"));

        private final Set<String> allowedOrigins =
                new LinkedHashSet<>(List.of("http://localhost:*", "http://127.0.0.1:*"));

        private Duration keepAliveInterval;

        private boolean behindTrustedTLSProxy;

        private MCPLimits limits = MCPLimits.defaults();

        private int maxSessions = 128;

        private Builder() {}

        /**
         * Sets the listener address.
         *
         * @param address bind address
         * @return this builder
         */
        public Builder bindAddress(InetAddress address) {
            bindAddress = address;
            return this;
        }

        /**
         * Sets the listener port.
         *
         * @param port zero for ephemeral, otherwise 1-65535
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets the endpoint path.
         *
         * @param endpoint absolute path
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Replaces allowed Host header patterns.
         *
         * @param hosts exact hosts or wildcard-port patterns
         * @return this builder
         */
        public Builder allowedHosts(Set<String> hosts) {
            allowedHosts.clear();
            allowedHosts.addAll(Objects.requireNonNull(hosts, "hosts"));
            return this;
        }

        /**
         * Replaces allowed Origin header patterns.
         *
         * @param origins exact origins or wildcard-port patterns
         * @return this builder
         */
        public Builder allowedOrigins(Set<String> origins) {
            allowedOrigins.clear();
            allowedOrigins.addAll(Objects.requireNonNull(origins, "origins"));
            return this;
        }

        /**
         * Enables periodic MCP keep-alive pings.
         *
         * @param interval positive interval
         * @return this builder
         */
        public Builder keepAliveInterval(Duration interval) {
            keepAliveInterval = interval;
            return this;
        }

        /**
         * Declares that a trusted TLS reverse proxy protects a non-loopback listener.
         *
         * @param trusted explicit deployment declaration
         * @return this builder
         */
        public Builder behindTrustedTLSProxy(boolean trusted) {
            behindTrustedTLSProxy = trusted;
            return this;
        }

        /**
         * Sets finite request and concurrency limits.
         *
         * @param limits limits
         * @return this builder
         */
        public Builder limits(MCPLimits limits) {
            this.limits = limits;
            return this;
        }

        /**
         * Sets the maximum simultaneously retained MCP sessions.
         *
         * <p>A slot is released by the client's MCP DELETE request. Abandoned clients keep their
         * bounded slot until server restart, favoring fail-closed memory behavior.
         *
         * @param maximum positive session bound
         * @return this builder
         */
        public Builder maxSessions(int maximum) {
            maxSessions = maximum;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return server options
         */
        public MCPStreamableHTTPServerOptions build() {
            return new MCPStreamableHTTPServerOptions(this);
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("IPv4 loopback is unavailable.", exception);
        }
    }

    private static String validateEndpoint(String endpoint) {
        String value = HostingMCPValidation.nonBlank(endpoint, "endpoint");
        if (!value.startsWith("/")
                || value.length() > 256
                || value.contains("..")
                || value.contains("?")
                || value.contains("#")) {
            throw new ValidationException("endpoint must be a short absolute non-traversing path.");
        }
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Set<String> copyNonBlank(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream()
                .map(value -> HostingMCPValidation.nonBlank(value, name + " entry"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
