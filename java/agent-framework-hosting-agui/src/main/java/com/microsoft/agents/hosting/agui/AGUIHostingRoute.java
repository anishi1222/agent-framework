// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingRouteDescriptor;
import com.microsoft.agents.hosting.HostingRouteKind;

/**
 * Binds one exact HTTP path to a generic hosted execution target.
 *
 * @param path exact AG-UI POST path
 * @param kind generic route kind
 * @param routeId generic route identifier
 * @param descriptor generic route descriptor
 */
public record AGUIHostingRoute(String path, HostingRouteKind kind, String routeId, HostingRouteDescriptor descriptor) {
    /** Creates a validated route binding. */
    public AGUIHostingRoute {
        path = AGUIHostingRegistry.validatePath(path);
        java.util.Objects.requireNonNull(kind, "kind");
        routeId = require(routeId, "routeId");
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.kind() != kind || !descriptor.id().equals(routeId)) {
            throw new IllegalArgumentException("AG-UI route and generic descriptor do not match.");
        }
    }

    /**
     * Returns the namespaced framework-extension capabilities path.
     *
     * @return capability path
     */
    public String capabilitiesPath() {
        return path.endsWith("/") ? path + "capabilities" : path + "/capabilities";
    }

    private static String require(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
