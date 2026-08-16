// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.a2a.A2ALimits;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Configures the embedded loopback-first A2A JSON-RPC/SSE host. */
public final class A2AHttpServerOptions {
    private final InetAddress bindAddress;

    private final int port;

    private final String endpoint;

    private final URI publicEndpoint;

    private final Set<String> allowedHosts;

    private final Set<String> allowedOrigins;

    private final boolean behindTrustedTlsProxy;

    private final A2AHostAuthenticator authenticator;

    private final A2ALimits limits;

    private final Duration closeTimeout;

    private A2AHttpServerOptions(Builder builder) {
        bindAddress = Objects.requireNonNull(builder.bindAddress, "bindAddress");
        if (builder.port < 0 || builder.port > 65_535) {
            throw new ValidationException("port must be zero or between 1 and 65535.");
        }
        port = builder.port;
        endpoint = endpoint(builder.endpoint);
        publicEndpoint = validatePublicEndpoint(builder.publicEndpoint, endpoint);
        allowedHosts = strings(builder.allowedHosts, "allowedHosts");
        allowedOrigins = strings(builder.allowedOrigins, "allowedOrigins");
        if (allowedHosts.isEmpty()) {
            throw new ValidationException("allowedHosts must not be empty.");
        }
        behindTrustedTlsProxy = builder.behindTrustedTlsProxy;
        authenticator = builder.authenticator;
        if (!bindAddress.isLoopbackAddress()) {
            if (!behindTrustedTlsProxy) {
                throw new ValidationException("Non-loopback A2A binding requires behindTrustedTlsProxy=true.");
            }
            if (authenticator == null) {
                throw new ValidationException("Non-loopback A2A binding requires an authenticator.");
            }
            if (publicEndpoint == null) {
                throw new ValidationException("Non-loopback A2A binding requires an HTTPS publicEndpoint.");
            }
            if (allowedOrigins.isEmpty()) {
                throw new ValidationException("Non-loopback A2A binding requires an Origin allowlist.");
            }
        }
        limits = Objects.requireNonNull(builder.limits, "limits");
        closeTimeout = positive(builder.closeTimeout, "closeTimeout");
    }

    /** Creates loopback-only options on an ephemeral port. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the listener address. */
    public InetAddress bindAddress() {
        return bindAddress;
    }

    /** Returns the requested port. */
    public int port() {
        return port;
    }

    /** Returns the JSON-RPC endpoint path. */
    public String endpoint() {
        return endpoint;
    }

    /** Returns the externally advertised endpoint, or {@code null} for computed loopback URI. */
    public URI publicEndpoint() {
        return publicEndpoint;
    }

    /** Returns Host header allowlist patterns. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Returns Origin header allowlist patterns. */
    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    /** Reports trusted TLS proxy declaration. */
    public boolean behindTrustedTlsProxy() {
        return behindTrustedTlsProxy;
    }

    /** Returns the authenticator, or {@code null} for loopback anonymous mode. */
    public A2AHostAuthenticator authenticator() {
        return authenticator;
    }

    /** Returns payload, parser, concurrency, and buffer limits. */
    public A2ALimits limits() {
        return limits;
    }

    /** Returns graceful close timeout. */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /** Builds immutable {@link A2AHttpServerOptions}. */
    public static final class Builder {
        private InetAddress bindAddress = loopback();

        private int port;

        private String endpoint = "/a2a";

        private URI publicEndpoint;

        private final Set<String> allowedHosts = new LinkedHashSet<>(List.of("localhost:*", "127.0.0.1:*", "[::1]:*"));

        private final Set<String> allowedOrigins =
                new LinkedHashSet<>(List.of("http://localhost:*", "http://127.0.0.1:*"));

        private boolean behindTrustedTlsProxy;

        private A2AHostAuthenticator authenticator;

        private A2ALimits limits = A2ALimits.defaults();

        private Duration closeTimeout = Duration.ofSeconds(10);

        private Builder() {}

        /** Sets listener address. */
        public Builder bindAddress(InetAddress value) {
            bindAddress = value;
            return this;
        }

        /** Sets zero for ephemeral or a concrete port. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets the JSON-RPC endpoint path. */
        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        /** Sets the HTTPS endpoint advertised when a trusted proxy terminates TLS. */
        public Builder publicEndpoint(URI value) {
            publicEndpoint = value;
            return this;
        }

        /** Replaces Host header patterns. */
        public Builder allowedHosts(Set<String> values) {
            allowedHosts.clear();
            allowedHosts.addAll(values);
            return this;
        }

        /** Replaces Origin header patterns. */
        public Builder allowedOrigins(Set<String> values) {
            allowedOrigins.clear();
            allowedOrigins.addAll(values);
            return this;
        }

        /** Declares a trusted TLS reverse proxy for non-loopback binding. */
        public Builder behindTrustedTlsProxy(boolean value) {
            behindTrustedTlsProxy = value;
            return this;
        }

        /** Sets authentication and principal-isolation resolution. */
        public Builder authenticator(A2AHostAuthenticator value) {
            authenticator = value;
            return this;
        }

        /** Sets finite host limits. */
        public Builder limits(A2ALimits value) {
            limits = value;
            return this;
        }

        /** Sets graceful close timeout. */
        public Builder closeTimeout(Duration value) {
            closeTimeout = value;
            return this;
        }

        /** Creates immutable options. */
        public A2AHttpServerOptions build() {
            return new A2AHttpServerOptions(this);
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("IPv4 loopback is unavailable.", exception);
        }
    }

    private static String endpoint(String value) {
        String path = HostingA2AValidation.nonBlank(value, "endpoint");
        if (!path.startsWith("/")
                || path.length() > 256
                || path.contains("..")
                || path.contains("?")
                || path.contains("#")) {
            throw new ValidationException("endpoint must be a short absolute non-traversing path.");
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Set<String> strings(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream()
                .map(value -> HostingA2AValidation.nonBlank(value, name + " entry"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static URI validatePublicEndpoint(URI value, String endpoint) {
        if (value == null) {
            return null;
        }
        if (!value.isAbsolute()
                || value.getHost() == null
                || !"https".equalsIgnoreCase(value.getScheme())
                || value.getUserInfo() != null
                || value.getFragment() != null
                || !endpoint.equals(value.getPath())) {
            throw new ValidationException(
                    "publicEndpoint must be an absolute HTTPS URI using the configured endpoint path.");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }
}
