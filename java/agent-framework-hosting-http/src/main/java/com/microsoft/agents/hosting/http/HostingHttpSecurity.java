// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validates Host, Origin, proxy, URI, and WebSocket upgrade security before dispatch. */
public final class HostingHttpSecurity {
    private static final Set<String> SINGLE_VALUE_HEADERS = Set.of(
            "authorization",
            "content-type",
            "cookie",
            "forwarded",
            "host",
            "origin",
            "proxy-authorization",
            "sec-websocket-extensions",
            "sec-websocket-key",
            "sec-websocket-protocol",
            "sec-websocket-version",
            "traceparent",
            "x-forwarded-proto",
            "x-request-id");

    private final HostingHttpServerOptions options;

    /**
     * Creates security validation from immutable server options.
     *
     * @param options server options
     */
    public HostingHttpSecurity(HostingHttpServerOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Validates one request before authentication or WebSocket upgrade.
     *
     * @param request request
     * @param webSocket whether the request is a WebSocket handshake
     */
    public void validate(HostingHttpRequest request, boolean webSocket) {
        Objects.requireNonNull(request, "request");
        validateUri(request.uri());
        validateUnambiguousHeaders(request);
        java.net.InetAddress peerAddress = request.remoteAddress().getAddress();
        if (options.transportSecurity() == HostingTransportSecurity.LOOPBACK_HTTP
                && (peerAddress == null || !peerAddress.isLoopbackAddress())) {
            throw new HostingException(HostingErrorCode.FORBIDDEN, "Loopback hosting rejects non-loopback peers.");
        }
        String host = singleHeader(request, "host", true);
        if (!matches(options.allowedHosts(), host, true)) {
            throw new HostingException(HostingErrorCode.FORBIDDEN, "Host header is not allowed.");
        }
        String origin = singleHeader(request, "origin", false);
        if (origin != null && !matches(options.allowedOrigins(), origin, false)) {
            throw new HostingException(HostingErrorCode.FORBIDDEN, "Origin is not allowed.");
        }
        if (webSocket && options.transportSecurity() == HostingTransportSecurity.TRUSTED_TLS_PROXY && origin == null) {
            throw new HostingException(
                    HostingErrorCode.FORBIDDEN, "A non-loopback WebSocket request requires an allowed Origin.");
        }
        if (options.transportSecurity() == HostingTransportSecurity.TRUSTED_TLS_PROXY) {
            validateForwardedHttps(request);
        }
        if (webSocket) {
            String extensions = singleHeader(request, "sec-websocket-extensions", false);
            if (extensions != null) {
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE,
                        "WebSocket extensions, including compression, are not enabled.");
            }
        }
    }

    /**
     * Returns an allowed exact origin for an optional CORS response.
     *
     * @param request request
     * @return exact origin, or {@code null}
     */
    public String corsOrigin(HostingHttpRequest request) {
        if (!options.corsEnabled()) {
            return null;
        }
        String origin = singleHeader(request, "origin", false);
        return origin != null && matches(options.allowedOrigins(), origin, false) ? origin : null;
    }

    private static void validateUri(URI uri) {
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new HostingException(
                    HostingErrorCode.MALFORMED_REQUEST,
                    "Hosting request URI must not contain user information, query, or fragment.");
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null
                || rawPath.isEmpty()
                || rawPath.contains("\\")
                || rawPath.contains("//")
                || rawPath.toLowerCase(Locale.ROOT).contains("%2f")
                || rawPath.toLowerCase(Locale.ROOT).contains("%5c")
                || java.util.Arrays.stream(rawPath.split("/", -1)).anyMatch(".."::equals)) {
            throw new HostingException(HostingErrorCode.MALFORMED_REQUEST, "Hosting request path is invalid.");
        }
    }

    private static void validateForwardedHttps(HostingHttpRequest request) {
        String forwardedProto = singleHeader(request, "x-forwarded-proto", false);
        String forwarded = singleHeader(request, "forwarded", false);
        boolean secure = forwardedProto != null && "https".equalsIgnoreCase(forwardedProto);
        if (!secure && forwarded != null) {
            String normalized = forwarded.toLowerCase(Locale.ROOT).replace(" ", "");
            secure = normalized.contains("proto=https");
        }
        if (!secure) {
            throw new HostingException(HostingErrorCode.FORBIDDEN, "Trusted TLS proxy request does not declare HTTPS.");
        }
    }

    private static String singleHeader(HostingHttpRequest request, String name, boolean required) {
        java.util.List<String> values = request.headers().get(name);
        if (values == null || values.isEmpty()) {
            if (required) {
                throw new HostingException(
                        HostingErrorCode.MALFORMED_REQUEST, "Required " + name + " header is absent.");
            }
            return null;
        }
        if (values.size() != 1
                || values.getFirst().isBlank()
                || values.getFirst().contains(",")) {
            throw new HostingException(
                    HostingErrorCode.MALFORMED_REQUEST, "Header " + name + " must have one unambiguous value.");
        }
        return values.getFirst();
    }

    private static void validateUnambiguousHeaders(HostingHttpRequest request) {
        SINGLE_VALUE_HEADERS.forEach(name -> {
            java.util.List<String> values = request.headers().get(name);
            if (values != null
                    && (values.size() != 1
                            || values.getFirst().isBlank()
                            || values.getFirst().contains("\r")
                            || values.getFirst().contains("\n"))) {
                throw new HostingException(
                        HostingErrorCode.MALFORMED_REQUEST, "Header " + name + " must have one unambiguous value.");
            }
        });
    }

    private static boolean matches(Set<String> patterns, String value, boolean ignoreCase) {
        for (String pattern : patterns) {
            if (matches(pattern, value, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String value, boolean ignoreCase) {
        String candidatePattern = ignoreCase ? pattern.toLowerCase(Locale.ROOT) : pattern;
        String candidateValue = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
        if (candidatePattern.endsWith(":*")) {
            String prefix = candidatePattern.substring(0, candidatePattern.length() - 1);
            return candidateValue.startsWith(prefix)
                    && candidateValue.length() > prefix.length()
                    && candidateValue.substring(prefix.length()).chars().allMatch(Character::isDigit);
        }
        return candidatePattern.equals(candidateValue);
    }
}
