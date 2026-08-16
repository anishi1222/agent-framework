// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.workflows.FunctionExecutor;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowBuilder;
import com.microsoft.agents.workflows.WorkflowNode;
import com.microsoft.agents.workflows.WorkflowRunOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkflowEvaluationAdapterTest {
    @Test
    void evaluateAsync_shouldRunWorkflowCasesAndMapOutputsInStableOrder() {
        // Arrange
        try (Workflow<String, String> workflow = textWorkflow(input -> "result:" + input)) {
            RecordingEvaluator evaluator = new RecordingEvaluator();
            WorkflowEvaluationOptions options =
                    new WorkflowEvaluationOptions("workflow parity", 2, WorkflowRunOptions.defaults());
            List<WorkflowEvaluationCase<String>> cases = List.of(
                    new WorkflowEvaluationCase<>(
                            "one",
                            EvaluationCase.builder(List.of(com.microsoft.agents.core.Message.text(
                                            com.microsoft.agents.core.Role.USER, "Q1")))
                                    .expectedOutput("result:one")
                                    .build()),
                    WorkflowEvaluationCase.text("two", "Q2"));

            // Act
            EvalResults results = new WorkflowEvaluationAdapter<>(workflow, WorkflowOutputMapper.text(value -> value))
                    .evaluateAsync(cases, evaluator, options, new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(evaluator.items()).extracting(EvalItem::query).containsExactly("Q1", "Q2", "Q1", "Q2");
            assertThat(evaluator.items())
                    .extracting(EvalItem::response)
                    .containsExactly("result:one", "result:two", "result:one", "result:two");
            assertThat(evaluator.items())
                    .extracting(EvalItem::expectedOutput)
                    .containsExactly("result:one", null, "result:one", null);
            assertThat(evaluator.evaluationName()).isEqualTo("workflow parity");
            assertThat(results.counts()).isEqualTo(new EvalCounts(4, 0, 0));
        }
    }

    @Test
    void outputMappers_shouldSupportMessagesAndAgentResponses() {
        // Arrange
        com.microsoft.agents.workflows.WorkflowState state = com.microsoft.agents.workflows.WorkflowState.empty();
        com.microsoft.agents.workflows.WorkflowRunResult<String> result =
                new com.microsoft.agents.workflows.WorkflowRunResult<>("run", "text", state, 1, null);

        // Act
        List<com.microsoft.agents.core.Message> messages = WorkflowOutputMapper.<String>messages(value -> List.of(
                        com.microsoft.agents.core.Message.text(com.microsoft.agents.core.Role.ASSISTANT, value)))
                .map(result);
        List<com.microsoft.agents.core.Message> responseMessages = WorkflowOutputMapper.<String>agentResponse(
                        value -> com.microsoft.agents.core.AgentResponse.builder()
                                .messages(List.of(com.microsoft.agents.core.Message.text(
                                        com.microsoft.agents.core.Role.ASSISTANT, value + "-response")))
                                .build())
                .map(result);

        // Assert
        assertThat(messages).extracting(com.microsoft.agents.core.Message::text).containsExactly("text");
        assertThat(responseMessages)
                .extracting(com.microsoft.agents.core.Message::text)
                .containsExactly("text-response");
    }

    @Test
    void evaluateAsync_shouldPropagateCancellationToPendingWorkflow() {
        // Arrange
        CompletableFuture<String> pending = new CompletableFuture<>();
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("pending-workflow", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "pending", FunctionExecutor.async(String.class, String.class, (input, context) -> pending));
        try (Workflow<String, String> workflow =
                builder.entry(node).output(node).build()) {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            CompletionStage<EvalResults> stage = new WorkflowEvaluationAdapter<>(
                            workflow, WorkflowOutputMapper.text(value -> value))
                    .evaluateAsync(
                            List.of(WorkflowEvaluationCase.text("input", "query")),
                            new RecordingEvaluator(),
                            WorkflowEvaluationOptions.defaults(),
                            cancellation);

            // Act
            cancellation.cancel();

            // Assert
            assertThatThrownBy(() -> stage.toCompletableFuture().join())
                    .hasCauseInstanceOf(RunCancelledException.class);
        }
    }

    @Test
    void evaluateAsync_shouldPassCancellationToEvaluator() {
        // Arrange
        try (Workflow<String, String> workflow = textWorkflow(input -> input)) {
            RecordingEvaluator evaluator = new RecordingEvaluator();
            DefaultRunCancellation cancellation = new DefaultRunCancellation();

            // Act
            new WorkflowEvaluationAdapter<>(workflow, WorkflowOutputMapper.text(value -> value))
                    .evaluateAsync(
                            List.of(WorkflowEvaluationCase.text("input", "query")),
                            evaluator,
                            WorkflowEvaluationOptions.defaults(),
                            cancellation)
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(evaluator.cancellation()).isSameAs(cancellation);
        }
    }

    @Test
    void evaluateAsync_shouldRejectEmptyCasesAndInvalidMapperOutput() {
        // Arrange
        try (Workflow<String, String> workflow = textWorkflow(input -> input)) {
            WorkflowEvaluationAdapter<String, String> adapter =
                    new WorkflowEvaluationAdapter<>(workflow, result -> null);

            // Act and assert
            assertThatThrownBy(() -> adapter.evaluateAsync(
                            List.of(),
                            new RecordingEvaluator(),
                            WorkflowEvaluationOptions.defaults(),
                            new DefaultRunCancellation()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one");
            assertThatThrownBy(() -> adapter.evaluateAsync(
                                    List.of(WorkflowEvaluationCase.text("input", "query")),
                                    new RecordingEvaluator(),
                                    WorkflowEvaluationOptions.defaults(),
                                    new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join())
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasRootCauseMessage("responseMessages");
        }
    }

    private static Workflow<String, String> textWorkflow(java.util.function.Function<String, String> function) {
        WorkflowBuilder<String, String> builder =
                WorkflowBuilder.create("evaluation-workflow", String.class, String.class);
        WorkflowNode<String, String> node = builder.addNode(
                "evaluate",
                FunctionExecutor.sync(String.class, String.class, (input, context) -> function.apply(input)));
        return builder.entry(node).output(node).build();
    }

    private static final class RecordingEvaluator implements Evaluator {
        private final AtomicReference<List<EvalItem>> items = new AtomicReference<>(List.of());
        private final AtomicReference<String> evaluationName = new AtomicReference<>();
        private final AtomicReference<RunCancellation> cancellation = new AtomicReference<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public CompletionStage<EvalResults> evaluateAsync(
                List<EvalItem> items, String evaluationName, RunCancellation cancellation) {
            this.items.set(List.copyOf(items));
            this.evaluationName.set(evaluationName);
            this.cancellation.set(cancellation);
            return new LocalEvaluator(EvaluationCheck.synchronous("always_pass", item -> CheckResult.pass("ok")))
                    .evaluateAsync(items, evaluationName, cancellation);
        }

        private List<EvalItem> items() {
            return items.get();
        }

        private String evaluationName() {
            return evaluationName.get();
        }

        private RunCancellation cancellation() {
            return cancellation.get();
        }
    }
}
