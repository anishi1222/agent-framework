// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.hosting.HostingRouteDescriptor;

/**
 * Binds one exact OpenAI Responses endpoint path to one generic hosted agent route.
 *
 * @param path exact endpoint path
 * @param routeId generic agent route identifier
 * @param model default OpenAI model value surfaced when the request omits one
 * @param descriptor generic route descriptor
 */
public record OpenAIResponsesHostingRoute(
        String path, String routeId, String model, HostingRouteDescriptor descriptor) {
    /** Creates a validated immutable binding. */
    public OpenAIResponsesHostingRoute {
        path = OpenAIResponsesHostingRegistry.validatePath(path);
        routeId = requireNonBlank(routeId, "routeId");
        model = requireNonBlank(model, "model");
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        if (!routeId.equals(descriptor.id())) {
            throw new IllegalArgumentException("routeId must match the generic route descriptor.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
