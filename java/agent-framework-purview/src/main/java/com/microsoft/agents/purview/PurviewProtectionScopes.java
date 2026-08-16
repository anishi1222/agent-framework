// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import java.util.List;

/**
 * Represents computed protection scopes and their concurrency token.
 *
 * @param scopes immutable scopes
 * @param etag optional quoted ETag for processContent
 * @param requestId optional service request identifier
 */
public record PurviewProtectionScopes(List<PurviewProtectionScope> scopes, String etag, String requestId) {
    /** Creates and defensively copies protection scopes. */
    public PurviewProtectionScopes {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        if (etag != null && etag.isBlank()) {
            throw new IllegalArgumentException("etag must not be blank.");
        }
    }
}
