// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowVizTest {
    @Test
    void render_shouldProduceDeterministicDotAndMermaidForDirectAndConditionalEdges() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("viz-basic", String.class, String.class);
        WorkflowNode<String, String> start = node(builder, "1 start");
        WorkflowNode<String, String> middle = node(builder, "middle|quoted\"");
        WorkflowNode<String, String> end = node(builder, "end");

        // Act
        try (Workflow<String, String> workflow = builder.entry(start)
                .output(end)
                .connectWhen(start, middle, value -> true)
                .connect(middle, end)
                .build()) {
            WorkflowViz viz = new WorkflowViz(workflow);
            String dot = viz.toDot();
            String mermaid = viz.toMermaid();

            // Assert
            assertThat(dot)
                    .startsWith("digraph Workflow {")
                    .contains("\"1 start\" [label=\"1 start\", shape=box, style=filled, fillcolor=lightgreen]")
                    .contains("\"1 start\" -> \"middle|quoted\\\"\" [style=dashed, label=\"conditional\"]")
                    .contains("\"middle|quoted\\\"\" -> \"end\"")
                    .contains("\"end\" [label=\"end\", shape=box, peripheries=2]");
            assertThat(mermaid)
                    .startsWith("flowchart TD")
                    .contains("n_1_start[\"1 start (Start)\"]")
                    .contains("middle_quoted[\"middle&#124;quoted&quot;\"]")
                    .contains("n_1_start -. conditional .-> middle_quoted")
                    .contains("middle_quoted --> end");
            assertThat(viz.render(WorkflowDiagramFormat.DOT)).isEqualTo(dot);
            assertThat(viz.render(WorkflowDiagramFormat.MERMAID)).isEqualTo(mermaid);
        }
    }

    @Test
    void render_shouldRouteFanInThroughOneSyntheticJunction_andFanOutToEveryTarget() {
        // Arrange
        WorkflowBuilder<Integer, Integer> builder = WorkflowBuilder.create("viz-groups", Integer.class, Integer.class);
        WorkflowNode<Integer, Integer> source = intNode(builder, "source");
        WorkflowNode<Integer, Integer> left = intNode(builder, "left");
        WorkflowNode<Integer, Integer> right = intNode(builder, "right");
        WorkflowNode<FanInInput, Integer> join =
                builder.addNode("join", FunctionExecutor.sync(FanInInput.class, Integer.class, (value, context) -> 1));

        // Act
        try (Workflow<Integer, Integer> workflow = builder.entry(source)
                .output(join)
                .fanOut(source, List.of(left, right))
                .fanIn(List.of(left, right), join)
                .build()) {
            WorkflowViz viz = new WorkflowViz(workflow);
            String dot = viz.toDot();
            String mermaid = viz.toMermaid();

            // Assert
            assertThat(dot)
                    .contains("\"source\" -> \"left\" [label=\"fan-out\"]")
                    .contains("\"source\" -> \"right\" [label=\"fan-out\"]")
                    .contains("\"__fan_in__join\" [shape=ellipse, label=\"fan-in\"")
                    .contains("\"left\" -> \"__fan_in__join\"")
                    .contains("\"right\" -> \"__fan_in__join\"")
                    .contains("\"__fan_in__join\" -> \"join\"")
                    .doesNotContain("\"left\" -> \"join\"")
                    .doesNotContain("\"right\" -> \"join\"");
            assertThat(mermaid)
                    .contains("source -->|fan-out| left")
                    .contains("source -->|fan-out| right")
                    .contains("__fan_in__join((fan-in))")
                    .contains("left --> __fan_in__join")
                    .contains("right --> __fan_in__join")
                    .contains("__fan_in__join --> join")
                    .doesNotContain("left --> join")
                    .doesNotContain("right --> join");
        }
    }

    @Test
    void mermaid_shouldResolveSanitizedAliasCollisionsDeterministically() {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("viz-alias", String.class, String.class);
        WorkflowNode<String, String> first = node(builder, "a-b");
        WorkflowNode<String, String> second = node(builder, "a b");

        // Act
        try (Workflow<String, String> workflow =
                builder.entry(first).output(second).connect(first, second).build()) {
            String mermaid = new WorkflowViz(workflow).toMermaid();

            // Assert
            assertThat(mermaid)
                    .contains("a_b[\"a b\"]")
                    .contains("a_b_2[\"a-b (Start)\"]")
                    .contains("a_b_2 --> a_b");
        }
    }

    @Test
    void write_shouldPersistExactUtf8Source(@TempDir Path directory) throws Exception {
        // Arrange
        WorkflowBuilder<String, String> builder = WorkflowBuilder.create("viz-write", String.class, String.class);
        WorkflowNode<String, String> only = node(builder, "only");
        Path output = directory.resolve("workflow.mmd");

        // Act
        try (Workflow<String, String> workflow =
                builder.entry(only).output(only).build()) {
            WorkflowViz viz = new WorkflowViz(workflow);
            Path written = viz.write(output, WorkflowDiagramFormat.MERMAID);

            // Assert
            assertThat(written).isEqualTo(output.toAbsolutePath().normalize());
            assertThat(Files.readString(output)).isEqualTo(viz.toMermaid());
        }
    }

    private static WorkflowNode<String, String> node(WorkflowBuilder<String, String> builder, String id) {
        return builder.addNode(id, FunctionExecutor.sync(String.class, String.class, (value, context) -> value));
    }

    private static WorkflowNode<Integer, Integer> intNode(WorkflowBuilder<Integer, Integer> builder, String id) {
        return builder.addNode(id, FunctionExecutor.sync(Integer.class, Integer.class, (value, context) -> value));
    }
}
