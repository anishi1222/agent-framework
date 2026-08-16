// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.hosting.HostingOrchestrationCodec;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRouteDescriptor;
import com.microsoft.agents.hosting.HostingWorkflowCodec;
import com.microsoft.agents.orchestrations.Orchestration;
import com.microsoft.agents.workflows.Workflow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registers exact AG-UI endpoint paths over one shared generic {@link HostingRegistry}. */
public final class AGUIHostingRegistry {
    /** Framework default endpoint path when an application binds one target. */
    public static final String DEFAULT_PATH = "/ag-ui";

    private final HostingRegistry hostingRegistry;

    private final Object lock = new Object();

    private final Map<String, AGUIHostingRoute> routes = new LinkedHashMap<>();

    /**
     * Creates an AG-UI route registry over a generic hosting registry.
     *
     * @param hostingRegistry shared generic registry
     */
    public AGUIHostingRegistry(HostingRegistry hostingRegistry) {
        this.hostingRegistry = java.util.Objects.requireNonNull(hostingRegistry, "hostingRegistry");
    }

    /**
     * Registers an agent at an exact endpoint path using its framework identifier.
     *
     * @param path endpoint path
     * @param agent agent
     * @return route
     */
    public AGUIHostingRoute registerAgent(String path, Agent<?> agent) {
        java.util.Objects.requireNonNull(agent, "agent");
        return register(path, hostingRegistry.registerAgent(agent));
    }

    /**
     * Registers an agent with an explicit route identifier.
     *
     * @param path endpoint path
     * @param routeId generic route identifier
     * @param agent agent
     * @return route
     */
    public AGUIHostingRoute registerAgent(String path, String routeId, Agent<?> agent) {
        return register(path, hostingRegistry.registerAgent(routeId, agent));
    }

    /**
     * Registers a workflow at an exact endpoint path.
     *
     * @param path endpoint path
     * @param routeId generic route identifier
     * @param workflow workflow
     * @param codec workflow input/output codec
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return route
     */
    public <I, O> AGUIHostingRoute registerWorkflow(
            String path, String routeId, Workflow<I, O> workflow, HostingWorkflowCodec<I, O> codec) {
        return register(path, hostingRegistry.registerWorkflow(routeId, workflow, codec));
    }

    /**
     * Registers an orchestration at an exact endpoint path.
     *
     * @param path endpoint path
     * @param routeId generic route identifier
     * @param orchestration orchestration
     * @param codec terminal output and resume codec
     * @param <O> orchestration output type
     * @return route
     */
    public <O> AGUIHostingRoute registerOrchestration(
            String path, String routeId, Orchestration<O> orchestration, HostingOrchestrationCodec<O> codec) {
        return register(path, hostingRegistry.registerOrchestration(routeId, orchestration, codec));
    }

    /**
     * Resolves an exact run or capabilities path.
     *
     * @param path request path
     * @return route
     */
    public Optional<AGUIHostingRoute> find(String path) {
        String checked = validatePath(path);
        synchronized (lock) {
            AGUIHostingRoute direct = routes.get(checked);
            if (direct != null) {
                return Optional.of(direct);
            }
            return routes.values().stream()
                    .filter(route -> route.capabilitiesPath().equals(checked))
                    .findFirst();
        }
    }

    /**
     * Lists route bindings in registration order.
     *
     * @return immutable routes
     */
    public List<AGUIHostingRoute> routes() {
        synchronized (lock) {
            return List.copyOf(routes.values());
        }
    }

    private AGUIHostingRoute register(String path, HostingRouteDescriptor descriptor) {
        String checked = validatePath(path);
        AGUIHostingRoute route = new AGUIHostingRoute(checked, descriptor.kind(), descriptor.id(), descriptor);
        synchronized (lock) {
            if (routes.containsKey(checked)
                    || routes.values().stream()
                            .anyMatch(existing -> existing.capabilitiesPath().equals(checked)
                                    || route.capabilitiesPath().equals(existing.path()))) {
                throw new IllegalArgumentException("AG-UI endpoint path is already registered or ambiguous.");
            }
            routes.put(checked, route);
        }
        return route;
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
            throw new IllegalArgumentException("AG-UI path must be one exact normalized absolute path.");
        }
        return value;
    }
}
