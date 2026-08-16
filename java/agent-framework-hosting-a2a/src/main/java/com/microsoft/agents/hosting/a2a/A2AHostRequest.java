// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exposes bounded request metadata to the host authentication SPI with redacted diagnostics. */
public final class A2AHostRequest {
    private final String method;

    private final URI uri;

    private final InetSocketAddress remoteAddress;

    private final Map<String, List<String>> headers;

    /**
     * Creates immutable request metadata.
     *
     * @param method HTTP method
     * @param uri request URI
     * @param remoteAddress immediate peer address
     * @param headers immutable header values
     */
    public A2AHostRequest(String method, URI uri, InetSocketAddress remoteAddress, Map<String, List<String>> headers) {
        this.method = HostingA2AValidation.nonBlank(method, "method");
        this.uri = HostingA2AValidation.required(uri, "uri");
        this.remoteAddress = HostingA2AValidation.required(remoteAddress, "remoteAddress");
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        this.headers = java.util.Collections.unmodifiableMap(copy);
    }

    /** Returns the HTTP method. */
    public String method() {
        return method;
    }

    /** Returns the request URI. */
    public URI uri() {
        return uri;
    }

    /** Returns the immediate peer address. */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns immutable headers.
     *
     * <p>Callers must not log credential-bearing values.
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return "A2AHostRequest[method=" + method + ", uri=" + uri + ", remoteAddress=" + remoteAddress
                + ", headers=<redacted>]";
    }
}
