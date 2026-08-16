// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configures an HTTPS MCP endpoint or an explicitly permitted loopback HTTP endpoint.
 *
 * @param endpoint absolute MCP endpoint
 * @param headers immutable request headers
 * @param tools explicitly allowed MCP tools
 * @param timeout tool-call timeout
 * @param allowInsecureLoopback whether loopback HTTP is allowed
 */
public record GitHubCopilotMCPHttpServerConfig(
        URI endpoint, Map<String, String> headers, List<String> tools, Duration timeout, boolean allowInsecureLoopback)
        implements GitHubCopilotMCPServerConfig {
    /** Creates and validates an HTTP MCP configuration. */
    public GitHubCopilotMCPHttpServerConfig {
        endpoint = Objects.requireNonNull(endpoint, "endpoint").normalize();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute host URI without user info or fragment.");
        }
        String scheme = endpoint.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equals(scheme)
                && !("http".equals(scheme) && allowInsecureLoopback && isLoopback(endpoint.getHost()))) {
            throw new IllegalArgumentException(
                    "endpoint must use HTTPS; HTTP is allowed only for explicitly enabled loopback.");
        }
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        headers.forEach((name, value) -> {
            if (name == null
                    || name.isBlank()
                    || name.indexOf('\0') >= 0
                    || value == null
                    || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("headers contain an invalid name or value.");
            }
        });
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        tools.forEach(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("tools must contain non-blank names.");
            }
        });
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
    }

    @Override
    public String toString() {
        return "GitHubCopilotMCPHttpServerConfig{endpoint="
                + endpoint
                + ", headerNames="
                + headers.keySet()
                + ", tools="
                + tools
                + ", timeout="
                + timeout
                + ", allowInsecureLoopback="
                + allowInsecureLoopback
                + '}';
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
