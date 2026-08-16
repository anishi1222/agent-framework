// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {
    @Test
    void builder_shouldCreateImmutableStronglyTypedGraph() {
        // Arrange
        WorkflowBuilder<String, Integer> builder = WorkflowBuilder.create("typed", String.class, Integer.class);
        WorkflowNode<String, Integer> start = builder.addNode(
                "start", FunctionExecutor.sync(String.class, Integer.class, (value, context) -> value.length()));
        WorkflowNode<Integer, Integer> finish = builder.addNode(
                "finish", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value + 1));

        // Act
        Workflow<String, Integer> workflow =
                builder.entry(start).output(finish).connect(start, finish).build();

        // Assert
        assertThat(workflow.nodes().keySet()).containsExactly(new NodeId("finish"), new NodeId("start"));
        assertThat(workflow.edges()).containsExactly(new DirectEdge(new NodeId("start"), new NodeId("finish")));
        assertThatThrownBy(() -> workflow.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        workflow.close();
    }

    @Test
    void builder_shouldRejectDuplicateNodeIdsAndRoutes() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("duplicates", String.class, String.class);
        WorkflowNode<String, String> node =
                builder.addNode("same", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));

        // Act / Assert
        assertThatThrownBy(() -> builder.addNode(
                        "same", FunctionExecutor.sync(String.class, String.class, (value, context) -> value)))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Duplicate workflow node id");
        assertThatThrownBy(() -> builder.entry(node)
                        .output(node)
                        .connect(node, node)
                        .connect(node, node)
                        .allowCycles()
                        .build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Duplicate workflow route");
    }

    @Test
    void builder_shouldRequireOneCombinedPredicateForSameConditionalRoute() {
        // Arrange
        WorkflowBuilder<Integer, Integer> builder =
                WorkflowBuilder.create("combined-predicate", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> target = builder.addNode(
                "target", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));

        // Act / Assert
        assertThatThrownBy(() -> builder.entry(source)
                        .output(target)
                        .connectWhen(source, target, value -> value > 0)
                        .connectWhen(source, target, value -> value % 2 == 0)
                        .build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("combine conditional predicates");
    }

    @Test
    void builder_shouldRejectMissingUnreachableAndIncompatibleNodes() {
        // Arrange
        WorkflowBuilder<String, String> missingBuilder = WorkflowBuilder.create("missing", String.class, String.class);
        WorkflowNode<String, String> start = missingBuilder.addNode(
                "start", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
        WorkflowBuilder<String, String> unreachableBuilder =
                WorkflowBuilder.create("unreachable", String.class, String.class);
        WorkflowNode<String, String> reachable = unreachableBuilder.addNode(
                "reachable", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
        unreachableBuilder.addNode(
                "orphan", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
        WorkflowBuilder<String, String> incompatibleBuilder =
                WorkflowBuilder.create("incompatible", String.class, String.class);
        WorkflowNode<String, Integer> source = incompatibleBuilder.addNode(
                "source", FunctionExecutor.sync(String.class, Integer.class, (value, context) -> value.length()));
        WorkflowNode<String, String> target = incompatibleBuilder.addNode(
                "target", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));

        // Act / Assert
        assertThatThrownBy(() -> missingBuilder
                        .entry(start)
                        .output(start)
                        .addEdge(new DirectEdge(new NodeId("start"), new NodeId("absent")))
                        .build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("missing node");
        assertThatThrownBy(() ->
                        unreachableBuilder.entry(reachable).output(reachable).build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("unreachable");
        assertThatThrownBy(() -> incompatibleBuilder
                        .entry(source)
                        .output(target)
                        .addEdge(new DirectEdge(source.id(), target.id()))
                        .build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Incompatible payload types");
    }

    @Test
    void builder_shouldRejectCyclesUnlessExplicitlyAllowed() {
        // Arrange
        WorkflowBuilder<String, String> rejected = WorkflowBuilder.create("cycle-rejected", String.class, String.class);
        WorkflowNode<String, String> rejectedNode =
                rejected.addNode("loop", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
        WorkflowBuilder<String, String> allowed = WorkflowBuilder.create("cycle-allowed", String.class, String.class);
        WorkflowNode<String, String> allowedNode =
                allowed.addNode("loop", FunctionExecutor.sync(String.class, String.class, (value, context) -> value));

        // Act / Assert
        assertThatThrownBy(() -> rejected.entry(rejectedNode)
                        .output(rejectedNode)
                        .connect(rejectedNode, rejectedNode)
                        .build())
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("cycle");
        try (Workflow<String, String> workflow = allowed.entry(allowedNode)
                .output(allowedNode)
                .connectWhen(allowedNode, allowedNode, value -> false)
                .allowCycles()
                .build()) {
            assertThat(workflow.edges()).hasSize(1);
        }
    }

    @Test
    void builder_shouldValidateFanInAndFanOutShapes() {
        // Arrange
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("groups", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = builder.addNode(
                "source", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
        WorkflowNode<Integer, Integer> left = builder.addNode(
                "left", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value + 1));
        WorkflowNode<Integer, Integer> right = builder.addNode(
                "right", FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value + 2));
        WorkflowNode<FanInInput, Integer> join = builder.addNode(
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        Integer.class,
                        (input, context) -> input.values(Integer.class).stream()
                                .mapToInt(Integer::intValue)
                                .sum()));

        // Act
        Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build();

        // Assert
        assertThat(workflow.edgeGroups())
                .extracting(group -> group.getClass().getSimpleName())
                .containsExactly("FanInEdgeGroup", "FanOutEdgeGroup");
        workflow.close();
    }

    @Test
    void builder_shouldKeepDelimiterLikeRouteIdsDistinct() {
        // Arrange
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("delimiter-routes", String.class, String.class);
        WorkflowNode<String, String> root = node(builder, "root");
        WorkflowNode<String, String> sourceA = node(builder, "a\u0000b");
        WorkflowNode<String, String> sourceB = node(builder, "a");
        WorkflowNode<String, String> targetA = node(builder, "c");
        WorkflowNode<String, String> targetB = node(builder, "b\u0000c");
        WorkflowNode<String, String> sink = node(builder, "sink");

        // Act
        Workflow<String, String> workflow = builder.entry(root)
                .output(sink)
                .fanOut(root, List.of(sourceA, sourceB))
                .connect(sourceA, targetA)
                .connect(sourceB, targetB)
                .connect(targetA, sink)
                .connect(targetB, sink)
                .build();

        // Assert
        assertThat(workflow.edges()).hasSize(4);
        workflow.close();
    }

    @Test
    void fingerprint_shouldSeparateAdversarialIdsAndExcludeReattachedBehavior() {
        // Arrange
        List<NodeId> ids = List.of(
                new NodeId("a"),
                new NodeId("a:b"),
                new NodeId("b:c"),
                new NodeId("c"),
                new NodeId("line"),
                new NodeId("line\nbreak"),
                new NodeId("break\ntail"),
                new NodeId("tail"),
                new NodeId("nul"),
                new NodeId("nul\u0000byte"),
                new NodeId("byte\u0000tail"));

        // Act
        try (Workflow<String, String> colonLeft = rawWorkflow(new DirectEdge(new NodeId("a:b"), new NodeId("c")), ids);
                Workflow<String, String> colonRight =
                        rawWorkflow(new DirectEdge(new NodeId("a"), new NodeId("b:c")), ids);
                Workflow<String, String> newlineLeft =
                        rawWorkflow(new DirectEdge(new NodeId("line\nbreak"), new NodeId("tail")), ids);
                Workflow<String, String> newlineRight =
                        rawWorkflow(new DirectEdge(new NodeId("line"), new NodeId("break\ntail")), ids);
                Workflow<String, String> nulLeft =
                        rawWorkflow(new DirectEdge(new NodeId("nul\u0000byte"), new NodeId("tail")), ids);
                Workflow<String, String> nulRight =
                        rawWorkflow(new DirectEdge(new NodeId("nul"), new NodeId("byte\u0000tail")), ids)) {
            // Assert
            assertThat(colonLeft.graphFingerprint()).isNotEqualTo(colonRight.graphFingerprint());
            assertThat(newlineLeft.graphFingerprint()).isNotEqualTo(newlineRight.graphFingerprint());
            assertThat(nulLeft.graphFingerprint()).isNotEqualTo(nulRight.graphFingerprint());
        }

        WorkflowBuilder<String, String> firstBuilder =
                WorkflowBuilder.create("behavior-excluded", String.class, String.class);
        WorkflowNode<String, String> first = node(firstBuilder, "node");
        WorkflowBuilder<String, String> secondBuilder =
                WorkflowBuilder.create("behavior-excluded", String.class, String.class);
        WorkflowNode<String, String> second = secondBuilder.addNode(
                "node", FunctionExecutor.sync(String.class, String.class, (value, context) -> value + "!"));
        try (Workflow<String, String> firstWorkflow = firstBuilder
                        .entry(first)
                        .output(first)
                        .connectWhen(first, first, value -> false)
                        .allowCycles()
                        .build();
                Workflow<String, String> secondWorkflow = secondBuilder
                        .entry(second)
                        .output(second)
                        .connectWhen(second, second, value -> value.isEmpty())
                        .allowCycles()
                        .build()) {
            assertThat(firstWorkflow.graphFingerprint()).isEqualTo(secondWorkflow.graphFingerprint());
        }
    }

    private static WorkflowNode<String, String> node(WorkflowBuilder<String, String> builder, String id) {
        return builder.addNode(id, FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
    }

    private static Workflow<String, String> rawWorkflow(Edge edge, List<NodeId> ids) {
        Map<NodeId, WorkflowNode<?, ?>> nodes = new LinkedHashMap<>();
        ids.forEach(id -> nodes.put(
                id,
                new WorkflowNode<>(id, FunctionExecutor.sync(String.class, String.class, (value, context) -> value))));
        return new Workflow<>(
                "adversarial",
                1,
                String.class,
                String.class,
                nodes,
                List.of(edge),
                List.of(),
                ids.getFirst(),
                ids.getLast(),
                false,
                null);
    }
}
