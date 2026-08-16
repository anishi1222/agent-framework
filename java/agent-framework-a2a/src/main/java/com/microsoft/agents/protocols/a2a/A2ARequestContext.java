// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.net.URI;

/**
 * Describes an outbound request for authentication-header selection.
 *
 * @param method A2A method or card operation
 * @param endpoint target endpoint
 * @param streaming whether the response is an SSE stream
 */
public record A2ARequestContext(String method, URI endpoint, boolean streaming) {
    /** Creates a validated request context. */
    public A2ARequestContext {
        method = A2AValidation.nonBlank(method, "method");
        endpoint = A2AValidation.absoluteUri(endpoint, "endpoint");
    }
}
