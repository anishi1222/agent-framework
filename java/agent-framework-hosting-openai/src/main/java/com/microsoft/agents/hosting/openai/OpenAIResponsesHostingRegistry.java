// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRouteDescriptor;
import com.microsoft.agents.hosting.HostingRouteKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registers exact OpenAI Responses endpoint paths over one shared generic hosting registry. */
public final class OpenAIResponsesHostingRegistry {
    /** Conventional single-agent OpenAI Responses endpoint. */
    public static final String DEFAULT_PATH = "/v1/responses";

    private final HostingRegistry hostingRegistry;

    private final Object lock = new Object();

    private final Map<String, OpenAIResponsesHostingRoute> routes = new LinkedHashMap<>();

    /**
     * Creates a route registry over a generic hosting registry.
     *
     * @param hostingRegistry shared generic registry
     */
    public OpenAIResponsesHostingRegistry(HostingRegistry hostingRegistry) {
        this.hostingRegistry = java.util.Objects.requireNonNull(hostingRegistry, "hostingRegistry");
    }

    /**
     * Registers an agent using its framework identifier as route and model.
     *
     * @param path exact endpoint path
     * @param agent agent target
     * @return route binding
     */
    public OpenAIResponsesHostingRoute registerAgent(String path, Agent<?> agent) {
        java.util.Objects.requireNonNull(agent, "agent");
        return register(path, agent.id(), agent.id(), agent);
    }

    /**
     * Registers an agent with explicit route and model identifiers.
     *
     * @param path exact endpoint path
     * @param routeId generic route identifier
     * @param model default response model identifier
     * @param agent agent target
     * @return route binding
     */
    public OpenAIResponsesHostingRoute register(String path, String routeId, String model, Agent<?> agent) {
        java.util.Objects.requireNonNull(agent, "agent");
        String checkedPath = validatePath(path);
        String checkedRouteId = requireNonBlank(routeId, "routeId");
        String checkedModel = requireNonBlank(model, "model");
        synchronized (lock) {
            requireAvailablePath(checkedPath);
            HostingRouteDescriptor descriptor = hostingRegistry.registerAgent(checkedRouteId, agent);
            OpenAIResponsesHostingRoute route =
                    new OpenAIResponsesHostingRoute(checkedPath, checkedRouteId, checkedModel, descriptor);
            routes.put(checkedPath, route);
            return route;
        }
    }

    /**
     * Binds an agent route that was already registered in the shared generic registry.
     *
     * @param path exact endpoint path
     * @param routeId existing generic route identifier
     * @param model default response model identifier
     * @return route binding
     */
    public OpenAIResponsesHostingRoute bind(String path, String routeId, String model) {
        String checkedPath = validatePath(path);
        String checkedRouteId = requireNonBlank(routeId, "routeId");
        String checkedModel = requireNonBlank(model, "model");
        synchronized (lock) {
            requireAvailablePath(checkedPath);
            HostingRouteDescriptor descriptor = hostingRegistry
                    .find(HostingRouteKind.AGENT, checkedRouteId)
                    .orElseThrow(() -> new IllegalArgumentException("Generic hosted agent route was not found."));
            OpenAIResponsesHostingRoute route =
                    new OpenAIResponsesHostingRoute(checkedPath, checkedRouteId, checkedModel, descriptor);
            routes.put(checkedPath, route);
            return route;
        }
    }

    /**
     * Resolves an exact endpoint path.
     *
     * @param path request path
     * @return route binding
     */
    public Optional<OpenAIResponsesHostingRoute> find(String path) {
        String checked = validatePath(path);
        synchronized (lock) {
            return Optional.ofNullable(routes.get(checked));
        }
    }

    /**
     * Lists bindings in registration order.
     *
     * @return immutable bindings
     */
    public List<OpenAIResponsesHostingRoute> routes() {
        synchronized (lock) {
            return List.copyOf(routes.values());
        }
    }

    static String validatePath(String value) {
        java.util.Objects.requireNonNull(value, "path");
        if (value.isBlank()
                || value.charAt(0) != '/'
                || value.length() > 1 && value.endsWith("/")
                || value.contains("//")
                || value.contains("\\")
                || value.contains("?")
                || value.contains("#")
                || java.util.Arrays.stream(value.split("/", -1)).anyMatch(".."::equals)) {
            throw new IllegalArgumentException("OpenAI Responses path must be one exact normalized absolute path.");
        }
        return value;
    }

    private void requireAvailablePath(String path) {
        if (routes.containsKey(path)) {
            throw new IllegalArgumentException("OpenAI Responses endpoint path is already registered.");
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
