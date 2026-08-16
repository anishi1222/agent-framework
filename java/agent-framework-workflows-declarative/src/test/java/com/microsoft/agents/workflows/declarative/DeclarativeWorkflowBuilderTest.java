// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.workflows.FanInInput;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeclarativeWorkflowBuilderTest {
    @Test
    void build_shouldConstructAndRunDeterministicProductionWorkflow() {
        WorkflowComponentRegistry registry = new WorkflowComponentRegistry(
                Map.of(
                        "normalize",
                        FunctionExecutor.sync(String.class, Integer.class, (input, context) -> Integer.valueOf(input)),
                        "decorate",
                        FunctionExecutor.sync(Integer.class, String.class, (input, context) -> "positive:" + input),
                        "fallback",
                        FunctionExecutor.sync(Integer.class, String.class, (input, context) -> "fallback:" + input),
                        "finish",
                        FunctionExecutor.sync(String.class, String.class, (input, context) -> input + "!")),
                Map.of(
                        "positive", new WorkflowCondition<>(Integer.class, value -> value >= 0),
                        "negative", new WorkflowCondition<>(Integer.class, value -> value < 0)));
        DeclarativeWorkflowDefinition definition = new DeclarativeWorkflowDefinition(
                "Workflow",
                "numbers",
                3,
                false,
                "start",
                "finish",
                List.of(
                        new DeclarativeNodeDefinition("start", "normalize"),
                        new DeclarativeNodeDefinition("positive", "decorate"),
                        new DeclarativeNodeDefinition("negative", "fallback"),
                        new DeclarativeNodeDefinition("finish", "finish")),
                List.of(
                        new ConditionalEdgeDefinition("start", "positive", "positive"),
                        new ConditionalEdgeDefinition("start", "negative", "negative"),
                        new DirectEdgeDefinition("positive", "finish"),
                        new DirectEdgeDefinition("negative", "finish")));
        DeclarativeWorkflowBuilder builder = new DeclarativeWorkflowBuilder(registry);

        try (Workflow<String, String> first = builder.build(definition, String.class, String.class);
                Workflow<String, String> second = builder.build(definition, String.class, String.class)) {
            WorkflowRunResult<String> positive = first.run("7");
            WorkflowRunResult<String> negative = first.run("-2");

            assertThat(positive.output()).isEqualTo("positive:7!");
            assertThat(negative.output()).isEqualTo("fallback:-2!");
            assertThat(first.schemaVersion()).isEqualTo(3);
            assertThat(first.graphFingerprint()).isEqualTo(second.graphFingerprint());
            assertThat(first.nodes().keySet().stream().map(Object::toString))
                    .containsExactly("finish", "negative", "positive", "start");
            assertThat(first.edges())
                    .extracting(edge -> edge.sourceId() + "->" + edge.targetId())
                    .containsExactly("negative->finish", "positive->finish", "start->negative", "start->positive");
        }
    }

    @Test
    void build_shouldSupportFanOutAndFanInThroughProductionBuilder() {
        WorkflowComponentRegistry registry = WorkflowComponentRegistry.ofExecutors(Map.of(
                "start",
                FunctionExecutor.sync(String.class, Integer.class, (input, context) -> Integer.valueOf(input)),
                "left",
                FunctionExecutor.sync(Integer.class, String.class, (input, context) -> "L" + input),
                "right",
                FunctionExecutor.sync(Integer.class, String.class, (input, context) -> "R" + input),
                "join",
                FunctionExecutor.sync(
                        FanInInput.class,
                        String.class,
                        (input, context) -> String.join("+", input.values(String.class)))));
        DeclarativeWorkflowDefinition definition = new DeclarativeWorkflowDefinition(
                "Workflow",
                "fan",
                1,
                false,
                "start",
                "join",
                List.of(
                        new DeclarativeNodeDefinition("start", "start"),
                        new DeclarativeNodeDefinition("left", "left"),
                        new DeclarativeNodeDefinition("right", "right"),
                        new DeclarativeNodeDefinition("join", "join")),
                List.of(
                        new FanOutEdgeDefinition("start", List.of("left", "right")),
                        new FanInEdgeDefinition(List.of("left", "right"), "join")));

        try (Workflow<String, String> workflow =
                new DeclarativeWorkflowBuilder(registry).build(definition, String.class, String.class)) {
            assertThat(workflow.run("4").output()).isEqualTo("L4+R4");
            assertThat(workflow.edgeGroups()).hasSize(2);
        }
    }

    @Test
    void build_shouldRejectMissingExecutorsAndConditions() {
        DeclarativeWorkflowDefinition missingExecutor = new DeclarativeWorkflowDefinition(
                "Workflow",
                "missing-executor",
                1,
                false,
                "a",
                "a",
                List.of(new DeclarativeNodeDefinition("a", "missing")),
                List.of());
        DeclarativeWorkflowBuilder emptyBuilder =
                new DeclarativeWorkflowBuilder(WorkflowComponentRegistry.ofExecutors(Map.of()));

        assertThatThrownBy(() -> emptyBuilder.build(missingExecutor, String.class, String.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("missing executor 'missing'");

        DeclarativeWorkflowDefinition missingCondition = new DeclarativeWorkflowDefinition(
                "Workflow",
                "missing-condition",
                1,
                false,
                "a",
                "b",
                List.of(new DeclarativeNodeDefinition("a", "a"), new DeclarativeNodeDefinition("b", "b")),
                List.of(new ConditionalEdgeDefinition("a", "b", "missing")));
        WorkflowComponentRegistry executors = WorkflowComponentRegistry.ofExecutors(Map.of(
                "a", FunctionExecutor.sync(String.class, String.class, (input, context) -> input),
                "b", FunctionExecutor.sync(String.class, String.class, (input, context) -> input)));

        assertThatThrownBy(() ->
                        new DeclarativeWorkflowBuilder(executors).build(missingCondition, String.class, String.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("missing condition 'missing'");
    }

    @Test
    void build_shouldRejectTypeAndGraphErrorsFromProductionBuilder() {
        WorkflowComponentRegistry incompatibleRegistry = WorkflowComponentRegistry.ofExecutors(Map.of(
                "source",
                FunctionExecutor.sync(String.class, Integer.class, (input, context) -> input.length()),
                "target",
                FunctionExecutor.sync(String.class, String.class, (input, context) -> input)));
        DeclarativeWorkflowDefinition incompatible = new DeclarativeWorkflowDefinition(
                "Workflow",
                "incompatible",
                1,
                false,
                "source",
                "target",
                List.of(
                        new DeclarativeNodeDefinition("source", "source"),
                        new DeclarativeNodeDefinition("target", "target")),
                List.of(new DirectEdgeDefinition("source", "target")));

        assertThatThrownBy(() -> new DeclarativeWorkflowBuilder(incompatibleRegistry)
                        .build(incompatible, String.class, String.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("Incompatible payload types");

        WorkflowComponentRegistry unreachableRegistry = WorkflowComponentRegistry.ofExecutors(Map.of(
                "a", FunctionExecutor.sync(String.class, String.class, (input, context) -> input),
                "b", FunctionExecutor.sync(String.class, String.class, (input, context) -> input)));
        DeclarativeWorkflowDefinition unreachable = new DeclarativeWorkflowDefinition(
                "Workflow",
                "unreachable",
                1,
                false,
                "a",
                "a",
                List.of(new DeclarativeNodeDefinition("a", "a"), new DeclarativeNodeDefinition("b", "b")),
                List.of());

        assertThatThrownBy(() -> new DeclarativeWorkflowBuilder(unreachableRegistry)
                        .build(unreachable, String.class, String.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("unreachable nodes");
    }

    @Test
    void build_shouldValidateConditionPayloadTypeAgainstSource() {
        WorkflowComponentRegistry registry = new WorkflowComponentRegistry(
                Map.of(
                        "a", FunctionExecutor.sync(String.class, Integer.class, (input, context) -> input.length()),
                        "b", FunctionExecutor.sync(Integer.class, Integer.class, (input, context) -> input)),
                Map.of("wrong", new WorkflowCondition<>(String.class, value -> true)));
        DeclarativeWorkflowDefinition definition = new DeclarativeWorkflowDefinition(
                "Workflow",
                "condition-type",
                1,
                false,
                "a",
                "b",
                List.of(new DeclarativeNodeDefinition("a", "a"), new DeclarativeNodeDefinition("b", "b")),
                List.of(new ConditionalEdgeDefinition("a", "b", "wrong")));

        assertThatThrownBy(
                        () -> new DeclarativeWorkflowBuilder(registry).build(definition, String.class, Integer.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("Conditional edge payload type");
    }

    @Test
    void build_shouldRequireExplicitCyclePolicy() {
        WorkflowComponentRegistry registry = WorkflowComponentRegistry.ofExecutors(Map.of(
                "a", FunctionExecutor.sync(String.class, String.class, (input, context) -> input),
                "b", FunctionExecutor.sync(String.class, String.class, (input, context) -> input)));
        List<DeclarativeNodeDefinition> nodes =
                List.of(new DeclarativeNodeDefinition("a", "a"), new DeclarativeNodeDefinition("b", "b"));
        List<DeclarativeEdgeDefinition> edges =
                List.of(new DirectEdgeDefinition("a", "b"), new DirectEdgeDefinition("b", "a"));
        DeclarativeWorkflowDefinition disallowed =
                new DeclarativeWorkflowDefinition("Workflow", "cycle", 1, false, "a", "b", nodes, edges);
        DeclarativeWorkflowDefinition allowed =
                new DeclarativeWorkflowDefinition("Workflow", "cycle", 1, true, "a", "b", nodes, edges);
        DeclarativeWorkflowBuilder builder = new DeclarativeWorkflowBuilder(registry);

        assertThatThrownBy(() -> builder.build(disallowed, String.class, String.class))
                .isInstanceOf(DeclarativeWorkflowValidationException.class)
                .hasMessageContaining("contains a cycle");
        try (Workflow<String, String> workflow = builder.build(allowed, String.class, String.class)) {
            assertThat(workflow.graphFingerprint()).isNotBlank();
        }
    }
}
