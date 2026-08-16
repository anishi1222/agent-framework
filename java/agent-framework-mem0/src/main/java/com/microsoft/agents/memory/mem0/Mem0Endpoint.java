// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import com.microsoft.agents.core.ValidationException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Defines a validated Mem0 Platform-compatible REST endpoint.
 *
 * <p>HTTPS is required except for syntactic loopback HTTP endpoints used by local development and
 * tests. User information, query strings, and fragments are rejected.
 */
public final class Mem0Endpoint {
    /** Current hosted Mem0 Platform endpoint. */
    public static final URI DEFAULT_URI = URI.create("https://api.mem0.ai/");

    private static final Mem0Endpoint PLATFORM = new Mem0Endpoint(DEFAULT_URI);

    private final URI uri;

    private Mem0Endpoint(URI uri) {
        this.uri = validate(uri);
    }

    /**
     * Returns the hosted Mem0 Platform endpoint.
     *
     * @return shared hosted endpoint value
     */
    public static Mem0Endpoint platform() {
        return PLATFORM;
    }

    /**
     * Creates a validated endpoint.
     *
     * @param uri absolute HTTPS URI or loopback HTTP URI
     * @return endpoint value
     */
    public static Mem0Endpoint of(URI uri) {
        return new Mem0Endpoint(uri);
    }

    /**
     * Creates a validated endpoint.
     *
     * @param uri absolute endpoint text
     * @return endpoint value
     */
    public static Mem0Endpoint of(String uri) {
        Objects.requireNonNull(uri, "uri");
        try {
            return of(URI.create(uri));
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Mem0 endpoint must be a valid absolute URI.", exception);
        }
    }

    /**
     * Returns the normalized endpoint URI.
     *
     * @return endpoint URI ending in a slash
     */
    public URI uri() {
        return uri;
    }

    URI resolve(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        URI relative;
        try {
            relative = URI.create(relativePath);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("Mem0 request path must be a valid fixed relative URI.", exception);
        }
        if (relativePath.isBlank()
                || relativePath.startsWith("/")
                || relative.isAbsolute()
                || relative.getRawAuthority() != null
                || relative.getRawFragment() != null
                || relative.getRawPath() == null
                || relative.getRawPath().isBlank()
                || hasDotSegment(relative.getRawPath())) {
            throw new ValidationException("Mem0 request path must be a fixed relative path.");
        }
        URI resolved = uri.resolve(relative);
        if (!Objects.equals(uri.getScheme(), resolved.getScheme())
                || !Objects.equals(uri.getHost(), resolved.getHost())
                || effectivePort(uri) != effectivePort(resolved)
                || resolved.getRawUserInfo() != null) {
            throw new ValidationException("Resolved Mem0 request URI escaped the configured endpoint.");
        }
        return resolved;
    }

    private static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Mem0Endpoint endpoint && uri.equals(endpoint.uri);
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
        Objects.requireNonNull(value, "uri");
        if (!value.isAbsolute() || value.getScheme() == null || value.getHost() == null) {
            throw new ValidationException("Mem0 endpoint must be an absolute URI with a host.");
        }
        if (value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new ValidationException("Mem0 endpoint must not contain user information, a query, or a fragment.");
        }
        String scheme = value.getScheme().toLowerCase(Locale.ROOT);
        String host = value.getHost();
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopbackHost(host))) {
            throw new ValidationException("Mem0 endpoint must use HTTPS; HTTP is restricted to loopback hosts.");
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
            int value = 0;
            for (int characterIndex = 0; characterIndex < octet.length(); characterIndex++) {
                char character = octet.charAt(characterIndex);
                if (character < '0' || character > '9') {
                    return false;
                }
                value = value * 10 + (character - '0');
            }
            if (value > 255 || index == 0 && value != 127) {
                return false;
            }
        }
        return true;
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }
}
