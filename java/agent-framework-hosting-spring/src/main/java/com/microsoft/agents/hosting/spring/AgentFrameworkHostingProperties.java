// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.spring;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.hosting.HostingAuthenticator;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.http.HostingHttpServerOptions;
import com.microsoft.agents.hosting.http.HostingTransportSecurity;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the opt-in Spring WebFlux adapter for the nonstandard Java hosting wire contract.
 *
 * <p>The adapter shares the containing application's reactive HTTP server. It does not open another
 * listener, install Spring Security, enable CORS, or expose WebSocket routing. The configured bind
 * address describes the application's listener trust boundary and defaults to loopback.
 */
@ConfigurationProperties(AgentFrameworkHostingProperties.PREFIX)
public final class AgentFrameworkHostingProperties {
    /** Configuration prefix. */
    public static final String PREFIX = "agent-framework.hosting";

    private boolean enabled;

    private String bindAddress = "127.0.0.1";

    private boolean trustedTlsProxy;

    private URI advertisedEndpoint;

    private Set<String> allowedHosts = new LinkedHashSet<>();

    private Set<String> allowedOrigins = new LinkedHashSet<>();

    private Set<String> trustedHeaderNames = new LinkedHashSet<>();

    private boolean corsEnabled;

    private int maxHttpHeaderBytes = 16 * 1024;

    private Duration gracefulShutdownTimeout = Duration.ofSeconds(10);

    /**
     * Reports whether Java hosting routes are enabled.
     *
     * @return enabled state
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables Java hosting routes.
     *
     * @param value enabled state
     */
    public void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Returns the application listener address used for hosting security validation.
     *
     * @return listener address
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Sets the application listener address used for hosting security validation.
     *
     * @param value listener address
     */
    public void setBindAddress(String value) {
        bindAddress = Objects.requireNonNull(value, "value");
    }

    /**
     * Reports whether the application is reachable only through a trusted TLS-terminating proxy.
     *
     * @return trusted-proxy state
     */
    public boolean isTrustedTlsProxy() {
        return trustedTlsProxy;
    }

    /**
     * Declares that the application is reachable only through a trusted TLS-terminating proxy.
     *
     * @param value trusted-proxy state
     */
    public void setTrustedTlsProxy(boolean value) {
        trustedTlsProxy = value;
    }

    /**
     * Returns the externally advertised HTTPS origin.
     *
     * @return advertised endpoint, or {@code null}
     */
    public URI getAdvertisedEndpoint() {
        return advertisedEndpoint;
    }

    /**
     * Sets the externally advertised HTTPS origin.
     *
     * @param value advertised endpoint
     */
    public void setAdvertisedEndpoint(URI value) {
        advertisedEndpoint = value;
    }

    /**
     * Returns the explicit Host allowlist.
     *
     * @return allowed hosts
     */
    public Set<String> getAllowedHosts() {
        return Set.copyOf(allowedHosts);
    }

    /**
     * Sets the explicit Host allowlist.
     *
     * @param values allowed hosts
     */
    public void setAllowedHosts(Set<String> values) {
        allowedHosts = new LinkedHashSet<>(Objects.requireNonNull(values, "values"));
    }

    /**
     * Returns the explicit Origin allowlist.
     *
     * @return allowed origins
     */
    public Set<String> getAllowedOrigins() {
        return Set.copyOf(allowedOrigins);
    }

    /**
     * Sets the explicit Origin allowlist.
     *
     * @param values allowed origins
     */
    public void setAllowedOrigins(Set<String> values) {
        allowedOrigins = new LinkedHashSet<>(Objects.requireNonNull(values, "values"));
    }

    /**
     * Returns request headers copied into trusted hosting context after authentication.
     *
     * @return trusted header names
     */
    public Set<String> getTrustedHeaderNames() {
        return Set.copyOf(trustedHeaderNames);
    }

    /**
     * Sets request headers copied into trusted hosting context after authentication.
     *
     * @param values trusted header names
     */
    public void setTrustedHeaderNames(Set<String> values) {
        trustedHeaderNames = new LinkedHashSet<>(Objects.requireNonNull(values, "values"));
    }

    /**
     * Reports whether exact-origin CORS response headers are enabled.
     *
     * @return CORS state
     */
    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    /**
     * Enables or disables exact-origin CORS response headers.
     *
     * @param value CORS state
     */
    public void setCorsEnabled(boolean value) {
        corsEnabled = value;
    }

    /**
     * Returns the declared HTTP request-header byte bound.
     *
     * @return header bytes
     */
    public int getMaxHttpHeaderBytes() {
        return maxHttpHeaderBytes;
    }

    /**
     * Sets the declared HTTP request-header byte bound.
     *
     * @param value header bytes
     */
    public void setMaxHttpHeaderBytes(int value) {
        maxHttpHeaderBytes = value;
    }

    /**
     * Returns the shutdown timeout used by shared transport options.
     *
     * @return shutdown timeout
     */
    public Duration getGracefulShutdownTimeout() {
        return gracefulShutdownTimeout;
    }

    /**
     * Sets the shutdown timeout used by shared transport options.
     *
     * @param value shutdown timeout
     */
    public void setGracefulShutdownTimeout(Duration value) {
        gracefulShutdownTimeout = Objects.requireNonNull(value, "value");
    }

    HostingHttpServerOptions toServerOptions(HostingLimits limits, HostingAuthenticator authenticator) {
        HostingHttpServerOptions.Builder builder = HostingHttpServerOptions.builder()
                .bindAddress(resolveBindAddress())
                .authenticator(authenticator)
                .limits(limits)
                .corsEnabled(corsEnabled)
                .maxHttpHeaderBytes(maxHttpHeaderBytes)
                .gracefulShutdownTimeout(gracefulShutdownTimeout)
                .trustedHeaderNames(trustedHeaderNames);
        if (!allowedHosts.isEmpty()) {
            builder.allowedHosts(allowedHosts);
        }
        if (!allowedOrigins.isEmpty()) {
            builder.allowedOrigins(allowedOrigins);
        }
        if (trustedTlsProxy) {
            builder.transportSecurity(HostingTransportSecurity.TRUSTED_TLS_PROXY)
                    .advertisedEndpoint(advertisedEndpoint);
        } else if (advertisedEndpoint != null) {
            builder.advertisedEndpoint(advertisedEndpoint);
        }
        return builder.build();
    }

    private InetAddress resolveBindAddress() {
        try {
            return InetAddress.getByName(bindAddress);
        } catch (UnknownHostException exception) {
            throw new ValidationException("bindAddress cannot be resolved.", exception);
        }
    }
}
