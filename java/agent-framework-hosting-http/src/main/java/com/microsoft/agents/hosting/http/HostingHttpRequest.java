// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.core.RunCancellation;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one complete bounded HTTP request without exposing servlet or application-framework
 * types.
 *
 * @param method request method
 * @param uri request URI
 * @param remoteAddress peer address
 * @param headers immutable lower-case header map
 * @param body complete bounded request body
 * @param cancellation disconnect and request cancellation signal
 */
public record HostingHttpRequest(
        String method,
        URI uri,
        InetSocketAddress remoteAddress,
        Map<String, List<String>> headers,
        byte[] body,
        RunCancellation cancellation) {
    /** Creates a validated immutable request. */
    public HostingHttpRequest {
        Objects.requireNonNull(method, "method");
        if (method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank.");
        }
        method = method.toUpperCase(Locale.ROOT);
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String normalized = Objects.requireNonNull(name, "header name").toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || copy.containsKey(normalized)) {
                throw new IllegalArgumentException("headers contain an invalid or duplicate name.");
            }
            copy.put(normalized, List.copyOf(Objects.requireNonNull(values, "header values")));
        });
        headers = Map.copyOf(copy);
        body = Objects.requireNonNull(body, "body").clone();
        Objects.requireNonNull(cancellation, "cancellation");
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the first value of a case-insensitive header.
     *
     * @param name header name
     * @return first value, or {@code null}
     */
    public String firstHeader(String name) {
        List<String> values = headers.get(Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
