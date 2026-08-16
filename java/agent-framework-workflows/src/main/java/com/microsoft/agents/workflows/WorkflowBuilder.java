// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

/**
 * Builds and validates an immutable, strongly typed workflow graph.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public final class WorkflowBuilder<I, O> {
    private final String workflowId;

    private final Class<I> inputType;

    private final Class<O> outputType;

    private final LinkedHashMap<NodeId, WorkflowNode<?, ?>> nodes = new LinkedHashMap<>();

    private final ArrayList<Edge> edges = new ArrayList<>();

    private final ArrayList<EdgeGroup> edgeGroups = new ArrayList<>();

    private int schemaVersion = 1;

    private boolean allowCycles;

    private WorkflowNode<I, ?> entryNode;

    private WorkflowNode<?, O> outputNode;

    private ExecutorService executorService;

    private WorkflowBuilder(String workflowId, Class<I> inputType, Class<O> outputType) {
        this.workflowId = WorkflowValidation.requireNonBlank(workflowId, "workflowId");
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
    }

    /**
     * Creates a workflow builder.
     *
     * @param workflowId stable workflow identity
     * @param inputType workflow input type
     * @param outputType workflow output type
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return workflow builder
     */
    public static <I, O> WorkflowBuilder<I, O> create(String workflowId, Class<I> inputType, Class<O> outputType) {
        return new WorkflowBuilder<>(workflowId, inputType, outputType);
    }

    /**
     * Sets the positive application schema version stored in checkpoints.
     *
     * @param schemaVersion application schema version
     * @return this builder
     */
    public WorkflowBuilder<I, O> schemaVersion(int schemaVersion) {
        if (schemaVersion <= 0) {
            throw new WorkflowValidationException("schemaVersion must be greater than zero.");
        }
        this.schemaVersion = schemaVersion;
        return this;
    }

    /**
     * Allows graph cycles whose runtime termination is controlled by conditional edges and the
     * max-superstep guard.
     *
     * @return this builder
     */
    public WorkflowBuilder<I, O> allowCycles() {
        allowCycles = true;
        return this;
    }

    /**
     * Uses a caller-owned executor service that the workflow will never close.
     *
     * @param executorService caller-owned executor service
     * @return this builder
     */
    public WorkflowBuilder<I, O> executorService(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "executorService");
        return this;
    }

    /**
     * Adds one typed executor node.
     *
     * @param id stable node identifier
     * @param executor typed node executor
     * @param <A> node input type
     * @param <B> node output type
     * @return typed node reference
     */
    public <A, B> WorkflowNode<A, B> addNode(String id, Executor<A, B> executor) {
        WorkflowNode<A, B> node = new WorkflowNode<>(new NodeId(id), executor);
        if (nodes.putIfAbsent(node.id(), node) != null) {
            throw new WorkflowValidationException("Duplicate workflow node id '" + node.id() + "'.");
        }
        return node;
    }

    /**
     * Selects the entry node.
     *
     * @param node entry node accepting the workflow input type
     * @return this builder
     */
    public WorkflowBuilder<I, O> entry(WorkflowNode<I, ?> node) {
        entryNode = Objects.requireNonNull(node, "node");
        return this;
    }

    /**
     * Selects the output node.
     *
     * @param node output node producing the workflow output type
     * @return this builder
     */
    public WorkflowBuilder<I, O> output(WorkflowNode<?, O> node) {
        outputNode = Objects.requireNonNull(node, "node");
        return this;
    }

    /**
     * Adds a strongly typed direct edge.
     *
     * @param source source node
     * @param target target node
     * @param <T> routed payload type
     * @return this builder
     */
    public <T> WorkflowBuilder<I, O> connect(WorkflowNode<?, T> source, WorkflowNode<T, ?> target) {
        return addEdge(new DirectEdge(source.id(), target.id()));
    }

    /**
     * Adds a strongly typed conditional edge.
     *
     * <p>Only one route may connect a given source/target pair. When several conditions should
     * route to the same target, combine them into one predicate with boolean OR. This prevents one
     * source output from scheduling the target more than once.
     *
     * @param source source node
     * @param target target node
     * @param condition routing predicate
     * @param <T> routed payload type
     * @return this builder
     */
    public <T> WorkflowBuilder<I, O> connectWhen(
            WorkflowNode<?, T> source, WorkflowNode<T, ?> target, Predicate<? super T> condition) {
        return addEdge(new ConditionalEdge<>(source.id(), target.id(), source.outputType(), condition));
    }

    /**
     * Adds a strongly typed fan-out group.
     *
     * @param source source node
     * @param targets targets accepting the source output
     * @param <T> routed payload type
     * @return this builder
     */
    public <T> WorkflowBuilder<I, O> fanOut(
            WorkflowNode<?, T> source, Collection<? extends WorkflowNode<T, ?>> targets) {
        Objects.requireNonNull(targets, "targets");
        return addEdgeGroup(new FanOutEdgeGroup(
                source.id(), targets.stream().map(WorkflowNode::id).toList()));
    }

    /**
     * Adds a fan-in group whose target accepts a typed-access {@link FanInInput}.
     *
     * @param sources required source nodes
     * @param target fan-in target
     * @return this builder
     */
    public WorkflowBuilder<I, O> fanIn(
            Collection<? extends WorkflowNode<?, ?>> sources, WorkflowNode<FanInInput, ?> target) {
        Objects.requireNonNull(sources, "sources");
        return addEdgeGroup(
                new FanInEdgeGroup(sources.stream().map(WorkflowNode::id).toList(), target.id()));
    }

    /**
     * Adds an explicit edge for graph import and validation scenarios.
     *
     * <p>The typed {@link #connect(WorkflowNode, WorkflowNode)} and {@link
     * #connectWhen(WorkflowNode, WorkflowNode, Predicate)} methods are preferred for application
     * construction. Explicit edges are fully type-checked by {@link #build()}.
     *
     * @param edge edge to add
     * @return this builder
     */
    public WorkflowBuilder<I, O> addEdge(Edge edge) {
        edges.add(Objects.requireNonNull(edge, "edge"));
        return this;
    }

    /**
     * Adds an explicit edge group for graph import and validation scenarios.
     *
     * @param edgeGroup edge group to add
     * @return this builder
     */
    public WorkflowBuilder<I, O> addEdgeGroup(EdgeGroup edgeGroup) {
        edgeGroups.add(Objects.requireNonNull(edgeGroup, "edgeGroup"));
        return this;
    }

    /**
     * Validates and creates an immutable workflow.
     *
     * @return immutable workflow
     */
    public Workflow<I, O> build() {
        validate();
        FeatureUsageIndexes.markCoreWorkflowUsed();
        TreeMap<NodeId, WorkflowNode<?, ?>> stableNodes = new TreeMap<>(nodes);
        ArrayList<Edge> stableEdges = new ArrayList<>(edges);
        stableEdges.sort(Comparator.comparing(Edge::sourceId)
                .thenComparing(Edge::targetId)
                .thenComparing(edge -> edge.getClass().getName()));
        ArrayList<EdgeGroup> stableGroups = new ArrayList<>(edgeGroups);
        stableGroups.sort(WorkflowGraphEncoding::compareEdgeGroups);
        return new Workflow<>(
                workflowId,
                schemaVersion,
                inputType,
                outputType,
                stableNodes,
                stableEdges,
                stableGroups,
                entryNode.id(),
                outputNode.id(),
                allowCycles,
                executorService);
    }

    private void validate() {
        if (entryNode == null) {
            throw new WorkflowValidationException("Workflow entry node is required.");
        }
        if (outputNode == null) {
            throw new WorkflowValidationException("Workflow output node is required.");
        }
        requireKnown(entryNode.id());
        requireKnown(outputNode.id());
        if (!entryNode.inputType().isAssignableFrom(inputType)) {
            throw new WorkflowValidationException("Entry node input type is incompatible with workflow input type.");
        }
        if (!outputType.isAssignableFrom(outputNode.outputType())) {
            throw new WorkflowValidationException("Output node type is incompatible with workflow output type.");
        }

        Set<RouteIdentity> routes = new HashSet<>();
        Set<NodeId> fanInTargets = new HashSet<>();
        Map<NodeId, Set<NodeId>> adjacency = new HashMap<>();
        nodes.keySet().forEach(id -> adjacency.put(id, new HashSet<>()));
        for (Edge edge : edges) {
            WorkflowNode<?, ?> source = requireKnown(edge.sourceId());
            WorkflowNode<?, ?> target = requireKnown(edge.targetId());
            requireCompatible(source.outputType(), target.inputType(), edge.sourceId(), edge.targetId());
            if (edge instanceof ConditionalEdge<?> conditional
                    && !conditional.payloadType().isAssignableFrom(source.outputType())) {
                throw new WorkflowValidationException(
                        "Conditional edge payload type is incompatible with source '" + edge.sourceId() + "'.");
            }
            addRoute(routes, adjacency, edge.sourceId(), edge.targetId());
        }
        for (EdgeGroup group : edgeGroups) {
            group.sourceIds().forEach(this::requireKnown);
            group.targetIds().forEach(this::requireKnown);
            if (group instanceof FanOutEdgeGroup fanOut) {
                WorkflowNode<?, ?> source = requireKnown(fanOut.sourceId());
                for (NodeId targetId : fanOut.targetIds()) {
                    WorkflowNode<?, ?> target = requireKnown(targetId);
                    requireCompatible(source.outputType(), target.inputType(), source.id(), target.id());
                    addRoute(routes, adjacency, source.id(), target.id());
                }
            } else if (group instanceof FanInEdgeGroup fanIn) {
                WorkflowNode<?, ?> target = requireKnown(fanIn.targetId());
                if (!fanInTargets.add(fanIn.targetId())) {
                    throw new WorkflowValidationException(
                            "Fan-in target '" + fanIn.targetId() + "' belongs to more than one fan-in group.");
                }
                if (!FanInInput.class.equals(target.inputType())) {
                    throw new WorkflowValidationException(
                            "Fan-in target '" + target.id() + "' must accept FanInInput.");
                }
                for (NodeId sourceId : fanIn.sourceIds()) {
                    addRoute(routes, adjacency, sourceId, fanIn.targetId());
                }
            }
        }

        validateReachability(adjacency);
        if (!allowCycles) {
            validateAcyclic(adjacency);
        }
    }

    private WorkflowNode<?, ?> requireKnown(NodeId id) {
        WorkflowNode<?, ?> node = nodes.get(id);
        if (node == null) {
            throw new WorkflowValidationException("Workflow edge references missing node '" + id + "'.");
        }
        return node;
    }

    private static void requireCompatible(Class<?> sourceType, Class<?> targetType, NodeId sourceId, NodeId targetId) {
        if (!targetType.isAssignableFrom(sourceType)) {
            throw new WorkflowValidationException("Incompatible payload types from node '"
                    + sourceId
                    + "' ("
                    + sourceType.getName()
                    + ") to node '"
                    + targetId
                    + "' ("
                    + targetType.getName()
                    + ").");
        }
    }

    private static void addRoute(
            Set<RouteIdentity> routes, Map<NodeId, Set<NodeId>> adjacency, NodeId sourceId, NodeId targetId) {
        if (!routes.add(new RouteIdentity(sourceId, targetId))) {
            throw new WorkflowValidationException("Duplicate workflow route from '" + sourceId + "' to '" + targetId
                    + "'; combine conditional predicates into one route.");
        }
        adjacency.get(sourceId).add(targetId);
    }

    private void validateReachability(Map<NodeId, Set<NodeId>> adjacency) {
        Set<NodeId> reached = new HashSet<>();
        ArrayDeque<NodeId> queue = new ArrayDeque<>();
        queue.add(entryNode.id());
        while (!queue.isEmpty()) {
            NodeId current = queue.removeFirst();
            if (reached.add(current)) {
                adjacency.get(current).stream().sorted().forEach(queue::addLast);
            }
        }
        List<NodeId> unreachable = nodes.keySet().stream()
                .filter(id -> !reached.contains(id))
                .sorted()
                .toList();
        if (!unreachable.isEmpty()) {
            throw new WorkflowValidationException("Workflow contains unreachable nodes: " + unreachable + ".");
        }
    }

    private static void validateAcyclic(Map<NodeId, Set<NodeId>> adjacency) {
        HashMap<NodeId, Visit> visits = new HashMap<>();
        for (NodeId nodeId : adjacency.keySet().stream().sorted().toList()) {
            visit(nodeId, adjacency, visits);
        }
    }

    private static void visit(NodeId nodeId, Map<NodeId, Set<NodeId>> adjacency, Map<NodeId, Visit> visits) {
        Visit current = visits.get(nodeId);
        if (current == Visit.ACTIVE) {
            throw new WorkflowValidationException(
                    "Workflow contains a cycle; call allowCycles() only when loop termination is explicit.");
        }
        if (current == Visit.COMPLETE) {
            return;
        }
        visits.put(nodeId, Visit.ACTIVE);
        for (NodeId targetId : adjacency.get(nodeId).stream().sorted().toList()) {
            visit(targetId, adjacency, visits);
        }
        visits.put(nodeId, Visit.COMPLETE);
    }

    private record RouteIdentity(NodeId sourceId, NodeId targetId) {}

    private enum Visit {
        ACTIVE,
        COMPLETE
    }
}
