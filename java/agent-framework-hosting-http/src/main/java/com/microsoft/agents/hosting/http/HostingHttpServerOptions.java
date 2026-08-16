// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingLimits;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Configures the loopback-first embedded Java hosting HTTP/SSE/WebSocket server. */
public final class HostingHttpServerOptions {
    private final InetAddress bindAddress;

    private final int port;

    private final URI advertisedEndpoint;

    private final HostingTransportSecurity transportSecurity;

    private final Set<String> allowedHosts;

    private final Set<String> allowedOrigins;

    private final Set<String> trustedHeaderNames;

    private final HostingAuthenticator authenticator;

    private final HostingLimits limits;

    private final boolean corsEnabled;

    private final int maxHttpHeaderBytes;

    private final Duration gracefulShutdownTimeout;

    private HostingHttpServerOptions(Builder builder) {
        bindAddress = Objects.requireNonNull(builder.bindAddress, "bindAddress");
        port = HttpHostingValidation.port(builder.port);
        advertisedEndpoint = validateAdvertised(builder.advertisedEndpoint);
        transportSecurity = Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
        allowedHosts = HttpHostingValidation.strings(builder.allowedHosts, "allowedHosts", true);
        allowedOrigins = HttpHostingValidation.strings(builder.allowedOrigins, "allowedOrigins", false);
        trustedHeaderNames = HttpHostingValidation.strings(builder.trustedHeaderNames, "trustedHeaderNames", true);
        authenticator = Objects.requireNonNull(builder.authenticator, "authenticator");
        limits = Objects.requireNonNull(builder.limits, "limits");
        corsEnabled = builder.corsEnabled;
        maxHttpHeaderBytes = HttpHostingValidation.positive(builder.maxHttpHeaderBytes, "maxHttpHeaderBytes");
        gracefulShutdownTimeout =
                HttpHostingValidation.positive(builder.gracefulShutdownTimeout, "gracefulShutdownTimeout");
        if (allowedHosts.isEmpty()) {
            throw new ValidationException("allowedHosts must not be empty.");
        }
        if (trustedHeaderNames.contains("authorization")
                || trustedHeaderNames.contains("cookie")
                || trustedHeaderNames.contains("proxy-authorization")) {
            throw new ValidationException("Credential headers must not be copied into trusted request context.");
        }
        boolean loopback = bindAddress.isLoopbackAddress();
        if (transportSecurity == HostingTransportSecurity.LOOPBACK_HTTP && !loopback) {
            throw new ValidationException("LOOPBACK_HTTP requires a loopback bind address.");
        }
        if (!loopback) {
            if (transportSecurity != HostingTransportSecurity.TRUSTED_TLS_PROXY) {
                throw new ValidationException("Non-loopback binding requires TRUSTED_TLS_PROXY.");
            }
            if (authenticator.isLocalOnly()) {
                throw new ValidationException("Non-loopback binding requires an application authenticator.");
            }
            if (!builder.hostsConfigured || !builder.originsConfigured || allowedOrigins.isEmpty()) {
                throw new ValidationException("Non-loopback binding requires explicit Host and Origin allowlists.");
            }
            if (advertisedEndpoint == null || !"https".equalsIgnoreCase(advertisedEndpoint.getScheme())) {
                throw new ValidationException("Trusted-proxy binding requires an explicit HTTPS advertised endpoint.");
            }
        }
        if (corsEnabled && allowedOrigins.isEmpty()) {
            throw new ValidationException("CORS requires an explicit Origin allowlist.");
        }
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
     * Returns the listener address.
     *
     * @return bind address
     */
    public InetAddress bindAddress() {
        return bindAddress;
    }

    /**
     * Returns the requested port, where zero selects an ephemeral port.
     *
     * @return port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the operator-declared external endpoint.
     *
     * @return endpoint, or {@code null} to derive a loopback endpoint
     */
    public URI advertisedEndpoint() {
        return advertisedEndpoint;
    }

    /**
     * Returns the network trust contract.
     *
     * @return transport security
     */
    public HostingTransportSecurity transportSecurity() {
        return transportSecurity;
    }

    /**
     * Returns the Host header allowlist.
     *
     * @return allowed hosts
     */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Returns the Origin allowlist.
     *
     * @return allowed origins
     */
    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /**
     * Returns headers copied into trusted request context after authentication.
     *
     * @return trusted names
     */
    public Set<String> trustedHeaderNames() {
        return trustedHeaderNames;
    }

    /**
     * Returns the authenticator.
     *
     * @return authenticator
     */
    public HostingAuthenticator authenticator() {
        return authenticator;
    }

    /**
     * Returns hosting limits.
     *
     * @return limits
     */
    public HostingLimits limits() {
        return limits;
    }

    /**
     * Reports whether exact-origin CORS response headers are enabled.
     *
     * @return CORS setting
     */
    public boolean corsEnabled() {
        return corsEnabled;
    }

    /**
     * Returns the HTTP request-header byte bound.
     *
     * @return bytes
     */
    public int maxHttpHeaderBytes() {
        return maxHttpHeaderBytes;
    }

    /**
     * Returns graceful shutdown timeout.
     *
     * @return timeout
     */
    public Duration gracefulShutdownTimeout() {
        return gracefulShutdownTimeout;
    }

    /** Builds immutable server options. */
    public static final class Builder {
        private InetAddress bindAddress = loopback();

        private int port;

        private URI advertisedEndpoint;

        private HostingTransportSecurity transportSecurity = HostingTransportSecurity.LOOPBACK_HTTP;

        private final Set<String> allowedHosts = new LinkedHashSet<>(List.of("localhost:*", "127.0.0.1:*", "[::1]:*"));

        private final Set<String> allowedOrigins = new LinkedHashSet<>(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://[::1]:*",
                "https://localhost:*",
                "https://127.0.0.1:*",
                "https://[::1]:*"));

        private final Set<String> trustedHeaderNames = new LinkedHashSet<>();

        private HostingAuthenticator authenticator = HostingAuthenticator.localOnly();

        private HostingLimits limits = HostingLimits.defaults();

        private boolean corsEnabled;

        private int maxHttpHeaderBytes = 16 * 1024;

        private Duration gracefulShutdownTimeout = Duration.ofSeconds(10);

        private boolean hostsConfigured;

        private boolean originsConfigured;

        private Builder() {}

        /** Sets the listener address. */
        public Builder bindAddress(InetAddress value) {
            bindAddress = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the listener port. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets the externally advertised origin without path, query, user information, or fragment. */
        public Builder advertisedEndpoint(URI value) {
            advertisedEndpoint = value;
            return this;
        }

        /** Sets the explicit network trust contract. */
        public Builder transportSecurity(HostingTransportSecurity value) {
            transportSecurity = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Replaces the Host header allowlist. */
        public Builder allowedHosts(Set<String> values) {
            allowedHosts.clear();
            allowedHosts.addAll(Objects.requireNonNull(values, "values"));
            hostsConfigured = true;
            return this;
        }

        /** Replaces the Origin allowlist. */
        public Builder allowedOrigins(Set<String> values) {
            allowedOrigins.clear();
            allowedOrigins.addAll(Objects.requireNonNull(values, "values"));
            originsConfigured = true;
            return this;
        }

        /** Replaces headers copied into trusted context after authentication. */
        public Builder trustedHeaderNames(Set<String> values) {
            trustedHeaderNames.clear();
            trustedHeaderNames.addAll(Objects.requireNonNull(values, "values"));
            return this;
        }

        /** Sets the application authenticator. */
        public Builder authenticator(HostingAuthenticator value) {
            authenticator = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets mandatory hosting limits. */
        public Builder limits(HostingLimits value) {
            limits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Enables or disables exact-origin CORS response headers. */
        public Builder corsEnabled(boolean value) {
            corsEnabled = value;
            return this;
        }

        /** Sets maximum HTTP request-header bytes. */
        public Builder maxHttpHeaderBytes(int value) {
            maxHttpHeaderBytes = value;
            return this;
        }

        /** Sets graceful shutdown timeout. */
        public Builder gracefulShutdownTimeout(Duration value) {
            gracefulShutdownTimeout = value;
            return this;
        }

        /**
         * Creates immutable options.
         *
         * @return options
         */
        public HostingHttpServerOptions build() {
            return new HostingHttpServerOptions(this);
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static URI validateAdvertised(URI value) {
        if (value == null) {
            return null;
        }
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getQuery() != null
                || value.getFragment() != null
                || (value.getPath() != null && !value.getPath().isEmpty() && !"/".equals(value.getPath()))) {
            throw new ValidationException("advertisedEndpoint must be an absolute origin without user information, "
                    + "path, query, or fragment.");
        }
        if (!"http".equalsIgnoreCase(value.getScheme()) && !"https".equalsIgnoreCase(value.getScheme())) {
            throw new ValidationException("advertisedEndpoint scheme must be HTTP or HTTPS.");
        }
        return value;
    }
}
