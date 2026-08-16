// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.Map;

/** Supplies per-request authentication headers without retaining them in client diagnostics. */
@FunctionalInterface
public interface A2AHeaderProvider {
    /**
     * Returns headers for one outbound request.
     *
     * @param context request context
     * @return headers, never {@code null}
     */
    Map<String, String> headers(A2ARequestContext context);

    /**
     * Returns a provider that supplies no headers.
     *
     * @return empty provider
     */
    static A2AHeaderProvider none() {
        return ignored -> Map.of();
    }
}
