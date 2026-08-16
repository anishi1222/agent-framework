// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Defines one validated Azure AI Search service endpoint.
 *
 * <p>HTTPS is required except for exact syntactic loopback HTTP endpoints used by deterministic
 * tests. User information, non-root paths, query strings, and fragments are rejected.
 */
public final class AzureAISearchEndpoint {
    private final URI uri;

    private AzureAISearchEndpoint(URI uri) {
        this.uri = validate(uri);
    }

    /**
     * Creates a validated endpoint.
     *
     * @param value absolute HTTPS URI or loopback HTTP URI
     * @return endpoint value
     */
    public static AzureAISearchEndpoint of(URI value) {
        return new AzureAISearchEndpoint(value);
    }

    /**
     * Creates a validated endpoint.
     *
     * @param value absolute endpoint text
     * @return endpoint value
     */
    public static AzureAISearchEndpoint of(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return of(URI.create(value));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Azure AI Search endpoint must be a valid absolute URI.", exception);
        }
    }

    /**
     * Returns the normalized service URI.
     *
     * @return URI ending in a slash
     */
    public URI uri() {
        return uri;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof AzureAISearchEndpoint endpoint && uri.equals(endpoint.uri);
    }

    @Override
    public int hashCode() {
        return uri.hashCode();
    }

    @Override
    public String toString() {
        return uri.toString();
    }

    private static URI validate(URI value) {
        Objects.requireNonNull(value, "value");
        if (!value.isAbsolute() || value.getScheme() == null || value.getHost() == null) {
            throw new ValidationException("Azure AI Search endpoint must be an absolute URI with a host.");
        }
        if (value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new ValidationException(
                    "Azure AI Search endpoint must not contain user information, a query, or a fragment.");
        }
        if (value.getRawPath() != null && !value.getRawPath().isEmpty() && !"/".equals(value.getRawPath())) {
            throw new ValidationException("Azure AI Search endpoint must not contain a non-root path.");
        }
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopbackHost(value.getHost()))) {
            throw new ValidationException(
                    "Azure AI Search endpoint must use HTTPS; HTTP is restricted to loopback hosts.");
        }
        String text = value.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equals(normalized) || "::1".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized)) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            int parsed = 0;
            for (int characterIndex = 0; characterIndex < octet.length(); characterIndex++) {
                char character = octet.charAt(characterIndex);
                if (character < '0' || character > '9') {
                    return false;
                }
                parsed = parsed * 10 + character - '0';
            }
            if (parsed > 255 || index == 0 && parsed != 127) {
                return false;
            }
        }
        return true;
    }
}
