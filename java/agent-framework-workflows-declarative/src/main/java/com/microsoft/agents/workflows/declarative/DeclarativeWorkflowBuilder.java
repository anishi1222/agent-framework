// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import com.microsoft.agents.workflows.ConditionalEdge;
import com.microsoft.agents.workflows.DirectEdge;
import com.microsoft.agents.workflows.Edge;
import com.microsoft.agents.workflows.Executor;
import com.microsoft.agents.workflows.FanInEdgeGroup;
import com.microsoft.agents.workflows.FanOutEdgeGroup;
import com.microsoft.agents.workflows.NodeId;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import com.microsoft.agents.workflows.WorkflowValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Builds validated declarative definitions through the production {@link WorkflowBuilder}. */
public final class DeclarativeWorkflowBuilder {
    private final WorkflowComponentRegistry registry;

    /**
     * Creates a builder using caller-owned workflow components.
     *
     * @param registry immutable executor and condition registry
     */
    public DeclarativeWorkflowBuilder(WorkflowComponentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Builds a workflow with a framework-owned virtual-thread executor.
     *
     * @param definition immutable declarative workflow definition
     * @param inputType workflow input type
     * @param outputType workflow output type
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return immutable production workflow
     */
    public <I, O> Workflow<I, O> build(
            DeclarativeWorkflowDefinition definition, Class<I> inputType, Class<O> outputType) {
        return build(definition, inputType, outputType, null);
    }

    /**
     * Builds a workflow using a caller-owned executor service.
     *
     * @param definition immutable declarative workflow definition
     * @param inputType workflow input type
     * @param outputType workflow output type
     * @param executorService caller-owned executor service
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return immutable production workflow
     */
    public <I, O> Workflow<I, O> build(
            DeclarativeWorkflowDefinition definition,
            Class<I> inputType,
            Class<O> outputType,
            ExecutorService executorService) {
        DeclarativeWorkflowDefinition checked = Objects.requireNonNull(definition, "definition");
        Class<I> checkedInput = Objects.requireNonNull(inputType, "inputType");
        Class<O> checkedOutput = Objects.requireNonNull(outputType, "outputType");
        try {
            WorkflowBuilder<I, O> builder = WorkflowBuilder.create(checked.id(), checkedInput, checkedOutput)
                    .schemaVersion(checked.schemaVersion());
            if (checked.allowCycles()) {
                builder.allowCycles();
            }
            if (executorService != null) {
                builder.executorService(executorService);
            }

            Map<String, WorkflowNode<?, ?>> nodes = new LinkedHashMap<>();
            for (DeclarativeNodeDefinition nodeDefinition : checked.nodes()) {
                Executor<?, ?> executor = registry.findExecutor(nodeDefinition.executor())
                        .orElseThrow(() -> new DeclarativeWorkflowValidationException("Workflow '"
                                + checked.id()
                                + "' node '"
                                + nodeDefinition.id()
                                + "' references missing executor '"
                                + nodeDefinition.executor()
                                + "'."));
                nodes.put(nodeDefinition.id(), addNode(builder, nodeDefinition.id(), executor));
            }

            builder.entry(entryNode(nodes.get(checked.entry()), checkedInput));
            builder.output(outputNode(nodes.get(checked.output()), checkedOutput));
            for (DeclarativeEdgeDefinition edge : checked.edges()) {
                addEdge(builder, edge, nodes);
            }
            return builder.build();
        } catch (DeclarativeWorkflowValidationException failure) {
            throw failure;
        } catch (WorkflowValidationException failure) {
            throw new DeclarativeWorkflowValidationException(
                    "Workflow '" + checked.id() + "' is invalid: " + failure.getMessage(), failure);
        }
    }

    private void addEdge(
            WorkflowBuilder<?, ?> builder, DeclarativeEdgeDefinition edge, Map<String, WorkflowNode<?, ?>> nodes) {
        switch (edge) {
            case DirectEdgeDefinition direct ->
                builder.addEdge(new DirectEdge(
                        nodes.get(direct.source()).id(),
                        nodes.get(direct.target()).id()));
            case ConditionalEdgeDefinition conditional -> {
                WorkflowCondition<?> condition = registry.findCondition(conditional.condition())
                        .orElseThrow(() -> new DeclarativeWorkflowValidationException(
                                "Conditional edge references missing condition '" + conditional.condition() + "'."));
                builder.addEdge(createConditionalEdge(
                        nodes.get(conditional.source()).id(),
                        nodes.get(conditional.target()).id(),
                        condition));
            }
            case FanOutEdgeDefinition fanOut ->
                builder.addEdgeGroup(new FanOutEdgeGroup(
                        nodes.get(fanOut.source()).id(),
                        fanOut.targets().stream()
                                .map(nodes::get)
                                .map(WorkflowNode::id)
                                .toList()));
            case FanInEdgeDefinition fanIn ->
                builder.addEdgeGroup(new FanInEdgeGroup(
                        fanIn.sources().stream()
                                .map(nodes::get)
                                .map(WorkflowNode::id)
                                .toList(),
                        nodes.get(fanIn.target()).id()));
        }
    }

    private static <A, B> WorkflowNode<A, B> addNode(
            WorkflowBuilder<?, ?> builder, String id, Executor<A, B> executor) {
        return builder.addNode(id, executor);
    }

    private static Edge createConditionalEdge(NodeId source, NodeId target, WorkflowCondition<?> condition) {
        return createConditionalEdgeCaptured(source, target, condition);
    }

    private static <T> ConditionalEdge<T> createConditionalEdgeCaptured(
            NodeId source, NodeId target, WorkflowCondition<T> condition) {
        return new ConditionalEdge<>(source, target, condition.payloadType(), condition.predicate());
    }

    @SuppressWarnings("unchecked")
    private static <I> WorkflowNode<I, ?> entryNode(WorkflowNode<?, ?> node, Class<I> workflowInputType) {
        if (!node.inputType().isAssignableFrom(workflowInputType)) {
            throw new DeclarativeWorkflowValidationException("Entry node '"
                    + node.id()
                    + "' input type "
                    + node.inputType().getName()
                    + " is incompatible with workflow input type "
                    + workflowInputType.getName()
                    + ".");
        }
        return (WorkflowNode<I, ?>) node;
    }

    @SuppressWarnings("unchecked")
    private static <O> WorkflowNode<?, O> outputNode(WorkflowNode<?, ?> node, Class<O> workflowOutputType) {
        if (!workflowOutputType.isAssignableFrom(node.outputType())) {
            throw new DeclarativeWorkflowValidationException("Output node '"
                    + node.id()
                    + "' output type "
                    + node.outputType().getName()
                    + " is incompatible with workflow output type "
                    + workflowOutputType.getName()
                    + ".");
        }
        return (WorkflowNode<?, O>) node;
    }
}
