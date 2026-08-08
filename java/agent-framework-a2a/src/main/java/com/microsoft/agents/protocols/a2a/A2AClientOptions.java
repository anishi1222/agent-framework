// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Configures the secure JDK HTTP A2A client.
 *
 * <p>HTTPS is required by default. Plain HTTP is accepted only when the endpoint is syntactically
 * loopback and {@link Builder#allowInsecureLoopbackHttp(boolean)} is explicitly enabled. Redirects
 * are always disabled by the implementation.
 */
public final class A2AClientOptions {
    private final URI endpoint;
    private final Set<String> allowedHosts;
    private final boolean allowInsecureLoopbackHttp;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration closeTimeout;
    private final A2ALimits limits;
    private final A2AHeaderProvider headerProvider;

    private A2AClientOptions(Builder builder) {
        endpoint = validateEndpoint(builder.endpoint, builder.allowInsecureLoopbackHttp);
        allowedHosts = copyHosts(builder.allowedHosts);
        String endpointHost = endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(endpointHost)) {
            throw new ValidationException("allowedHosts must contain the endpoint host.");
        }
        allowInsecureLoopbackHttp = builder.allowInsecureLoopbackHttp;
        connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        requestTimeout = positive(builder.requestTimeout, "requestTimeout");
        closeTimeout = positive(builder.closeTimeout, "closeTimeout");
        limits = Objects.requireNonNull(builder.limits, "limits");
        headerProvider = Objects.requireNonNull(builder.headerProvider, "headerProvider");
    }

    /**
     * Creates options for one endpoint.
     *
     * @param endpoint JSON-RPC endpoint or agent base URL
     * @return builder
     */
    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    /** Returns the configured endpoint. */
    public URI endpoint() {
        return endpoint;
    }

    /** Returns exact allowed host names. */
    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /** Reports explicit loopback HTTP opt-in. */
    public boolean allowInsecureLoopbackHttp() {
        return allowInsecureLoopbackHttp;
    }

    /** Returns the connection timeout. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Returns the per-request timeout. */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Returns the graceful close timeout. */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /** Returns finite protocol limits. */
    public A2ALimits limits() {
        return limits;
    }

    /** Returns the per-request header provider. */
    public A2AHeaderProvider headerProvider() {
        return headerProvider;
    }

    /** Builds immutable {@link A2AClientOptions}. */
    public static final class Builder {
        private final URI endpoint;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private boolean allowInsecureLoopbackHttp;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration closeTimeout = Duration.ofSeconds(5);
        private A2ALimits limits = A2ALimits.defaults();
        private A2AHeaderProvider headerProvider = A2AHeaderProvider.none();

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            if (endpoint.getHost() != null) {
                allowedHosts.add(endpoint.getHost().toLowerCase(Locale.ROOT));
            }
        }

        /** Replaces the exact host allowlist. */
        public Builder allowedHosts(Set<String> hosts) {
            allowedHosts.clear();
            allowedHosts.addAll(Objects.requireNonNull(hosts, "hosts"));
            return this;
        }

        /** Explicitly allows plain HTTP only for loopback endpoints. */
        public Builder allowInsecureLoopbackHttp(boolean value) {
            allowInsecureLoopbackHttp = value;
            return this;
        }

        /** Sets the connection timeout. */
        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        /** Sets the request and initial stream-response timeout. */
        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        /** Sets the graceful close timeout. */
        public Builder closeTimeout(Duration value) {
            closeTimeout = value;
            return this;
        }

        /** Sets finite limits. */
        public Builder limits(A2ALimits value) {
            limits = value;
            return this;
        }

        /** Sets the per-request authentication-header provider. */
        public Builder headerProvider(A2AHeaderProvider value) {
            headerProvider = value;
            return this;
        }

        /** Creates immutable options. */
        public A2AClientOptions build() {
            return new A2AClientOptions(this);
        }
    }

    private static URI validateEndpoint(URI endpoint, boolean allowLoopbackHttp) {
        A2AValidation.absoluteUri(endpoint, "endpoint");
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new ValidationException("A2A endpoint must not contain user information or a fragment.");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return endpoint;
        }
        if ("http".equals(scheme) && allowLoopbackHttp && isSyntacticLoopback(endpoint.getHost())) {
            return endpoint;
        }
        throw new ValidationException("A2A endpoint must use HTTPS; plain HTTP requires explicit loopback opt-in.");
    }

    private static boolean isSyntacticLoopback(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value);
    }

    private static Set<String> copyHosts(Set<String> hosts) {
        if (hosts.isEmpty()) {
            throw new ValidationException("allowedHosts must not be empty.");
        }
        return hosts.stream()
                .map(host -> A2AValidation.nonBlank(host, "allowedHosts entry").toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new ValidationException(name + " must be positive.");
        }
        return value;
    }
}
