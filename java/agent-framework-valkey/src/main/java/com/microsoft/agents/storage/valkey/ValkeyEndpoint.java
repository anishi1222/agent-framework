// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.ValidationException;

/**
 * Identifies one standalone Valkey endpoint.
 *
 * @param host exact DNS name or IP literal without a URI scheme or credentials
 * @param port TCP port
 */
public record ValkeyEndpoint(String host, int port) {
    /** Creates a validated standalone endpoint. */
    public ValkeyEndpoint {
        host = ValkeyValidation.host(host);
        if (port <= 0 || port > 65_535) {
            throw new ValidationException("port must be between 1 and 65535.");
        }
    }

    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
