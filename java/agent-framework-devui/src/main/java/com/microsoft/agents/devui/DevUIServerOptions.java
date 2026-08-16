// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Configures the loopback-first embedded developer UI and generic JSON/SSE adapter. */
public final class DevUIServerOptions {
    private final HostingHttpServerOptions transportOptions;

    private final DevUITransportSecurity transportSecurity;

    private final boolean allowNonLoopback;

    private DevUIServerOptions(Builder builder) {
        InetAddress bindAddress = Objects.requireNonNull(builder.bindAddress, "bindAddress");
        allowNonLoopback = builder.allowNonLoopback;
        if (!bindAddress.isLoopbackAddress() && !allowNonLoopback) {
            throw new ValidationException("Non-loopback Dev UI binding requires allowNonLoopback=true.");
        }
        transportSecurity = Objects.requireNonNull(builder.transportSecurity, "transportSecurity");
        HostingHttpServerOptions.Builder transport = HostingHttpServerOptions.builder()
                .bindAddress(bindAddress)
                .port(builder.port)
                .transportSecurity(transportSecurity.toHostingTransportSecurity())
                .authenticator(Objects.requireNonNull(builder.authenticator, "authenticator"))
                .limits(Objects.requireNonNull(builder.limits, "limits"))
                .trustedHeaderNames(builder.trustedHeaderNames)
                .maxHttpHeaderBytes(builder.maxHttpHeaderBytes)
                .gracefulShutdownTimeout(builder.gracefulShutdownTimeout)
                .corsEnabled(false);
        if (builder.advertisedEndpoint != null) {
            transport.advertisedEndpoint(builder.advertisedEndpoint);
        }
        if (builder.hostsConfigured) {
            transport.allowedHosts(builder.allowedHosts);
        }
        if (builder.originsConfigured) {
            transport.allowedOrigins(builder.allowedOrigins);
        }
        transportOptions = transport.build();
    }

    /**
     * Creates a loopback-only options builder using an ephemeral port.
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
        return transportOptions.bindAddress();
    }

    /**
     * Returns the requested port, where zero selects an ephemeral port.
     *
     * @return port
     */
    public int port() {
        return transportOptions.port();
    }

    /**
     * Returns the operator-declared external origin.
     *
     * @return origin, or {@code null} for a derived loopback origin
     */
    public URI advertisedEndpoint() {
        return transportOptions.advertisedEndpoint();
    }

    /**
     * Returns the network trust contract.
     *
     * @return transport security
     */
    public DevUITransportSecurity transportSecurity() {
        return transportSecurity;
    }

    /**
     * Reports whether the operator explicitly allowed a non-loopback listener.
     *
     * @return non-loopback opt-in
     */
    public boolean allowNonLoopback() {
        return allowNonLoopback;
    }

    /**
     * Returns the Host header allowlist.
     *
     * @return allowed hosts
     */
    public Set<String> allowedHosts() {
        return transportOptions.allowedHosts();
    }

    /**
     * Returns the Origin header allowlist.
     *
     * @return allowed origins
     */
    public Set<String> allowedOrigins() {
        return transportOptions.allowedOrigins();
    }

    /**
     * Returns headers copied into trusted request context after authentication.
     *
     * @return trusted header names
     */
    public Set<String> trustedHeaderNames() {
        return transportOptions.trustedHeaderNames();
    }

    /**
     * Returns the application authenticator.
     *
     * @return authenticator
     */
    public HostingAuthenticator authenticator() {
        return transportOptions.authenticator();
    }

    /**
     * Returns generic hosting limits.
     *
     * @return hosting limits
     */
    public HostingLimits limits() {
        return transportOptions.limits();
    }

    /**
     * Returns the HTTP request-header byte bound.
     *
     * @return maximum header bytes
     */
    public int maxHttpHeaderBytes() {
        return transportOptions.maxHttpHeaderBytes();
    }

    /**
     * Returns the graceful shutdown timeout.
     *
     * @return shutdown timeout
     */
    public Duration gracefulShutdownTimeout() {
        return transportOptions.gracefulShutdownTimeout();
    }

    HostingHttpServerOptions transportOptions() {
        return transportOptions;
    }

    /** Builds immutable developer UI options. */
    public static final class Builder {
        private InetAddress bindAddress = loopback();

        private int port;

        private URI advertisedEndpoint;

        private DevUITransportSecurity transportSecurity = DevUITransportSecurity.LOOPBACK_HTTP;

        private boolean allowNonLoopback;

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

        /** Sets the externally advertised origin without a path, query, or fragment. */
        public Builder advertisedEndpoint(URI value) {
            advertisedEndpoint = value;
            return this;
        }

        /** Sets the explicit network trust contract. */
        public Builder transportSecurity(DevUITransportSecurity value) {
            transportSecurity = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Explicitly allows or denies binding the developer UI beyond loopback. */
        public Builder allowNonLoopback(boolean value) {
            allowNonLoopback = value;
            return this;
        }

        /** Replaces the Host header allowlist. */
        public Builder allowedHosts(Set<String> values) {
            Set<String> copy = Set.copyOf(Objects.requireNonNull(values, "values"));
            allowedHosts.clear();
            allowedHosts.addAll(copy);
            hostsConfigured = true;
            return this;
        }

        /** Replaces the Origin header allowlist. */
        public Builder allowedOrigins(Set<String> values) {
            Set<String> copy = Set.copyOf(Objects.requireNonNull(values, "values"));
            allowedOrigins.clear();
            allowedOrigins.addAll(copy);
            originsConfigured = true;
            return this;
        }

        /** Replaces headers copied into trusted request context after authentication. */
        public Builder trustedHeaderNames(Set<String> values) {
            Set<String> copy = Set.copyOf(Objects.requireNonNull(values, "values"));
            trustedHeaderNames.clear();
            trustedHeaderNames.addAll(copy);
            return this;
        }

        /** Sets the application authenticator. */
        public Builder authenticator(HostingAuthenticator value) {
            authenticator = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets mandatory generic hosting limits. */
        public Builder limits(HostingLimits value) {
            limits = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets the maximum HTTP request-header bytes accepted by the adapter. */
        public Builder maxHttpHeaderBytes(int value) {
            maxHttpHeaderBytes = value;
            return this;
        }

        /** Sets the graceful shutdown timeout. */
        public Builder gracefulShutdownTimeout(Duration value) {
            gracefulShutdownTimeout = value;
            return this;
        }

        /**
         * Creates immutable developer UI options.
         *
         * @return options
         */
        public DevUIServerOptions build() {
            return new DevUIServerOptions(this);
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
