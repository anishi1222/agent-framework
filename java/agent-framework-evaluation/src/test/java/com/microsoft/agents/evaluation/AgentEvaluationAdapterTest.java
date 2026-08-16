// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentEvaluationAdapterTest {
    @Test
    void evaluateAsync_shouldRunCasesAndRepetitionsInDeterministicOrder() {
        // Arrange
        ScriptedAgent agent = ScriptedAgent.immediate();
        RecordingEvaluator evaluator = new RecordingEvaluator();
        AgentEvaluationOptions options = new AgentEvaluationOptions("agent parity", 2, RunOptions.empty());
        List<EvaluationCase> cases = List.of(
                EvaluationCase.builder(List.of(Message.text(Role.USER, "Q1")))
                        .expectedOutput("A1")
                        .build(),
                EvaluationCase.builder(List.of(Message.text(Role.USER, "Q2")))
                        .expectedOutput("A2")
                        .build());

        // Act
        EvalResults results = new AgentEvaluationAdapter<>(agent)
                .evaluateAsync(cases, evaluator, options, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(agent.queries()).containsExactly("Q1", "Q2", "Q1", "Q2");
        assertThat(evaluator.items()).extracting(EvalItem::query).containsExactly("Q1", "Q2", "Q1", "Q2");
        assertThat(evaluator.items()).extracting(EvalItem::expectedOutput).containsExactly("A1", "A2", "A1", "A2");
        assertThat(evaluator.items())
                .extracting(EvalItem::response)
                .containsExactly("reply:Q1", "reply:Q2", "reply:Q1", "reply:Q2");
        assertThat(results.counts()).isEqualTo(new EvalCounts(4, 0, 0));
        assertThat(evaluator.evaluationName()).isEqualTo("agent parity");
    }

    @Test
    void evaluateAsync_shouldPassSameCancellationToAgentAndEvaluator() {
        // Arrange
        ScriptedAgent agent = ScriptedAgent.immediate();
        RecordingEvaluator evaluator = new RecordingEvaluator();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        new AgentEvaluationAdapter<>(agent)
                .evaluateAsync(
                        List.of(EvaluationCase.text("Q")), evaluator, AgentEvaluationOptions.defaults(), cancellation)
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(agent.lastCancellation()).isSameAs(cancellation);
        assertThat(evaluator.cancellation()).isSameAs(cancellation);
    }

    @Test
    void evaluateAsync_shouldPropagateCancellationToPendingAgentRun() {
        // Arrange
        ScriptedAgent agent = ScriptedAgent.pending();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletionStage<EvalResults> stage = new AgentEvaluationAdapter<>(agent)
                .evaluateAsync(
                        List.of(EvaluationCase.text("Q")),
                        new RecordingEvaluator(),
                        AgentEvaluationOptions.defaults(),
                        cancellation);

        // Act
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture().join()).hasCauseInstanceOf(RunCancelledException.class);
        assertThat(agent.lastCancellation()).isSameAs(cancellation);
    }

    @Test
    void evaluateAsync_shouldPropagateCancellationToPendingEvaluator() {
        // Arrange
        ScriptedAgent agent = ScriptedAgent.immediate();
        PendingEvaluator evaluator = new PendingEvaluator();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletionStage<EvalResults> stage = new AgentEvaluationAdapter<>(agent)
                .evaluateAsync(
                        List.of(EvaluationCase.text("Q")), evaluator, AgentEvaluationOptions.defaults(), cancellation);

        // Act
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture().join()).hasCauseInstanceOf(RunCancelledException.class);
        assertThat(evaluator.cancellation()).isSameAs(cancellation);
        assertThat(evaluator.pending()).isCancelled();
    }

    @Test
    void evaluateAsync_shouldRejectEmptyCasesAndInvalidRepetitions() {
        // Arrange
        AgentEvaluationAdapter<Void> adapter = new AgentEvaluationAdapter<>(ScriptedAgent.immediate());

        // Act and assert
        assertThatThrownBy(() -> adapter.evaluateAsync(
                        List.of(),
                        new RecordingEvaluator(),
                        AgentEvaluationOptions.defaults(),
                        new DefaultRunCancellation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> new AgentEvaluationOptions("test", 0, RunOptions.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repetitions");
    }

    private static final class ScriptedAgent implements Agent<Void> {
        private final boolean pending;
        private final List<String> queries = new ArrayList<>();
        private final AtomicReference<RunCancellation> lastCancellation = new AtomicReference<>();

        private ScriptedAgent(boolean pending) {
            this.pending = pending;
        }

        private static ScriptedAgent immediate() {
            return new ScriptedAgent(false);
        }

        private static ScriptedAgent pending() {
            return new ScriptedAgent(true);
        }

        @Override
        public AgentMetadata metadata() {
            return new AgentMetadata("scripted", "Scripted", null);
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            lastCancellation.set(cancellation);
            String query = messages.getLast().text();
            queries.add(query);
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            if (!pending) {
                source.tryComplete(AgentResponse.<Void>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "reply:" + query)))
                        .build());
            }
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException("Streaming is not used by these tests.");
        }

        private List<String> queries() {
            return List.copyOf(queries);
        }

        private RunCancellation lastCancellation() {
            return lastCancellation.get();
        }
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

    private static final class PendingEvaluator implements Evaluator {
        private final CompletableFuture<EvalResults> pending = new CompletableFuture<>();
        private final AtomicReference<RunCancellation> cancellation = new AtomicReference<>();

        @Override
        public String name() {
            return "pending";
        }

        @Override
        public CompletionStage<EvalResults> evaluateAsync(
                List<EvalItem> items, String evaluationName, RunCancellation cancellation) {
            this.cancellation.set(cancellation);
            return pending;
        }

        private CompletableFuture<EvalResults> pending() {
            return pending;
        }

        private RunCancellation cancellation() {
            return cancellation.get();
        }
    }
}
