// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes validated transport metadata presented to authentication.
 *
 * @param method uppercase request method
 * @param uri request URI without user information or fragment
 * @param remoteAddress peer address
 * @param headers immutable case-normalized raw headers
 */
public record HostingTransportRequest(
        String method, URI uri, InetSocketAddress remoteAddress, Map<String, List<String>> headers) {
    /** Creates a validated immutable request. */
    public HostingTransportRequest {
        method = HostingValidation.nonBlank(method, "method").toUpperCase(java.util.Locale.ROOT);
        uri = Objects.requireNonNull(uri, "uri");
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Transport request URI must not contain user information or a fragment.");
        }
        remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        headers = HostingValidation.copyHeaders(headers);
    }

    /**
     * Returns the first value for a normalized header name.
     *
     * @param name header name
     * @return first value, or {@code null}
     */
    public String firstHeader(String name) {
        List<String> values =
                headers.get(HostingValidation.nonBlank(name, "name").toLowerCase(java.util.Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
