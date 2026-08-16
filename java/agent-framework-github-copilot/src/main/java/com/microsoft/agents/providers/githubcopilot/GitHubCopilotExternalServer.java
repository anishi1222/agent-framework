// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Identifies an already-running loopback Copilot CLI server consumed by the official SDK.
 *
 * <p>The official Java SDK currently connects with a plain TCP socket. Remote hosts are therefore
 * rejected rather than implying TLS or server authentication that the transport does not provide.
 */
public final class GitHubCopilotExternalServer {
    private final String host;

    private final int port;

    private final String connectionToken;

    /**
     * Creates a loopback external-server configuration.
     *
     * @param host loopback host name or address
     * @param port TCP port
     * @param connectionToken non-blank connection authentication token
     */
    public GitHubCopilotExternalServer(String host, int port, String connectionToken) {
        String requestedHost = requireNonBlank(host, "host");
        InetAddress address = resolveLoopback(requestedHost);
        this.host = address.getHostAddress();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535.");
        }
        this.port = port;
        this.connectionToken = requireNonBlank(connectionToken, "connectionToken");
    }

    /**
     * Returns the loopback host.
     *
     * @return loopback host
     */
    public String host() {
        return host;
    }

    /**
     * Returns the TCP port.
     *
     * @return TCP port
     */
    public int port() {
        return port;
    }

    String connectionToken() {
        return connectionToken;
    }

    String authorityHost() {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    @Override
    public String toString() {
        return "GitHubCopilotExternalServer{host='" + host + "', port=" + port + ", connectionToken=[REDACTED]}";
    }

    private static InetAddress resolveLoopback(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress()) {
                throw new IllegalArgumentException(
                        "External Copilot CLI TCP is restricted to loopback because the SDK transport has no TLS.");
            }
            return address;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("External Copilot CLI host cannot be resolved.", exception);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
