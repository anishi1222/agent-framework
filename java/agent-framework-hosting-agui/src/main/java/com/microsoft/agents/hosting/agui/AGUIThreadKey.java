// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingRouteKind;

/**
 * Identifies thread state by trusted principal, independent isolation, hosted route, and correlation
 * thread.
 *
 * @param principalId authenticated principal identifier
 * @param isolationId independently derived isolation identifier
 * @param routeKind hosted route family
 * @param routeId hosted route identifier
 * @param threadId untrusted AG-UI correlation identifier
 */
public record AGUIThreadKey(
        String principalId, String isolationId, HostingRouteKind routeKind, String routeId, String threadId) {
    /** Creates a validated thread key. */
    public AGUIThreadKey {
        principalId = require(principalId, "principalId");
        isolationId = require(isolationId, "isolationId");
        java.util.Objects.requireNonNull(routeKind, "routeKind");
        routeId = require(routeId, "routeId");
        threadId = require(threadId, "threadId");
    }

    private static String require(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
