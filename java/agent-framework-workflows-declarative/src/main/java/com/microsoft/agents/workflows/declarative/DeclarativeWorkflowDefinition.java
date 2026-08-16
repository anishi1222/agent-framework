// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Defines one immutable workflow topology independently of executor implementations.
 *
 * @param kind required root kind, {@code Workflow}
 * @param id stable workflow identifier
 * @param schemaVersion positive application schema version
 * @param allowCycles whether the production workflow builder may accept graph cycles
 * @param entry entry node identifier
 * @param output output node identifier
 * @param nodes immutable node definitions
 * @param edges immutable edge and edge-group definitions
 */
public record DeclarativeWorkflowDefinition(
        String kind,
        String id,
        int schemaVersion,
        boolean allowCycles,
        String entry,
        String output,
        List<DeclarativeNodeDefinition> nodes,
        List<DeclarativeEdgeDefinition> edges) {
    /** Creates and validates an immutable workflow definition. */
    public DeclarativeWorkflowDefinition {
        kind = WorkflowDefinitionValidation.requireNonBlank(kind, "kind");
        if (!kind.equals("Workflow")) {
            throw new DeclarativeWorkflowValidationException(
                    "Unsupported workflow kind '" + kind + "'; expected 'Workflow'.");
        }
        id = WorkflowDefinitionValidation.requireNonBlank(id, "id");
        if (schemaVersion <= 0) {
            throw new DeclarativeWorkflowValidationException("schemaVersion must be greater than zero.");
        }
        entry = WorkflowDefinitionValidation.requireNonBlank(entry, "entry");
        output = WorkflowDefinitionValidation.requireNonBlank(output, "output");
        nodes = copyNodes(nodes);
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (edges.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("edges contains null");
        }
        validateTopology(entry, output, nodes, edges);
    }

    private static List<DeclarativeNodeDefinition> copyNodes(List<DeclarativeNodeDefinition> values) {
        List<DeclarativeNodeDefinition> copy = List.copyOf(Objects.requireNonNull(values, "nodes"));
        if (copy.isEmpty()) {
            throw new DeclarativeWorkflowValidationException("Workflow requires at least one node.");
        }
        LinkedHashMap<String, DeclarativeNodeDefinition> byId = new LinkedHashMap<>();
        for (DeclarativeNodeDefinition node : copy) {
            Objects.requireNonNull(node, "nodes contains null");
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new DeclarativeWorkflowValidationException("Duplicate workflow node id '" + node.id() + "'.");
            }
        }
        return copy;
    }

    private static void validateTopology(
            String entry, String output, List<DeclarativeNodeDefinition> nodes, List<DeclarativeEdgeDefinition> edges) {
        Set<String> nodeIds =
                nodes.stream().map(DeclarativeNodeDefinition::id).collect(java.util.stream.Collectors.toSet());
        requireKnown(nodeIds, entry, "entry");
        requireKnown(nodeIds, output, "output");

        Set<Route> routes = new HashSet<>();
        Set<String> fanInTargets = new HashSet<>();
        for (DeclarativeEdgeDefinition edge : edges) {
            switch (edge) {
                case DirectEdgeDefinition direct -> addRoute(nodeIds, routes, direct.source(), direct.target());
                case ConditionalEdgeDefinition conditional ->
                    addRoute(nodeIds, routes, conditional.source(), conditional.target());
                case FanOutEdgeDefinition fanOut -> {
                    requireKnown(nodeIds, fanOut.source(), "edge source");
                    for (String target : fanOut.targets()) {
                        addRoute(nodeIds, routes, fanOut.source(), target);
                    }
                }
                case FanInEdgeDefinition fanIn -> {
                    requireKnown(nodeIds, fanIn.target(), "edge target");
                    if (!fanInTargets.add(fanIn.target())) {
                        throw new DeclarativeWorkflowValidationException(
                                "Fan-in target '" + fanIn.target() + "' belongs to more than one fan-in edge.");
                    }
                    for (String source : fanIn.sources()) {
                        addRoute(nodeIds, routes, source, fanIn.target());
                    }
                }
            }
        }
    }

    private static void addRoute(Set<String> nodeIds, Set<Route> routes, String source, String target) {
        requireKnown(nodeIds, source, "edge source");
        requireKnown(nodeIds, target, "edge target");
        if (!routes.add(new Route(source, target))) {
            throw new DeclarativeWorkflowValidationException(
                    "Duplicate workflow route from '" + source + "' to '" + target + "'.");
        }
    }

    private static void requireKnown(Set<String> nodeIds, String nodeId, String role) {
        if (!nodeIds.contains(nodeId)) {
            throw new DeclarativeWorkflowValidationException(
                    "Workflow " + role + " references missing node '" + nodeId + "'.");
        }
    }

    private record Route(String source, String target) {}
}
