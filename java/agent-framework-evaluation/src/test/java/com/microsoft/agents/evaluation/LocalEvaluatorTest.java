// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class LocalEvaluatorTest {
    @Test
    void evaluateAsync_shouldAggregatePassingAndFailingItems() {
        // Arrange
        LocalEvaluator evaluator = new LocalEvaluator(
                EvalChecks.keyword("weather"),
                EvaluationCheck.synchronous(
                        "length", item -> CheckResult.scored(item.response().length(), 10, "response length")));
        List<EvalItem> items = List.of(EvalItem.of("Q1", "The weather is sunny."), EvalItem.of("Q2", "No"));

        // Act
        EvalResults results = evaluate(evaluator, items);

        // Assert
        assertThat(results.provider()).isEqualTo("Local");
        assertThat(results.counts()).isEqualTo(new EvalCounts(1, 1, 0));
        assertThat(results.items())
                .extracting(EvalItemResult::status)
                .containsExactly(EvalItemStatus.PASS, EvalItemStatus.FAIL);
        assertThat(results.perEvaluator())
                .containsEntry("keyword", new EvalCounts(1, 1, 0))
                .containsEntry("length", new EvalCounts(1, 1, 0));
        assertThat(results.allPassed()).isFalse();
    }

    @Test
    void evaluateAsync_shouldFailItemsWhenNoChecksProvideEvidence() {
        // Arrange
        LocalEvaluator evaluator = new LocalEvaluator();

        // Act
        EvalResults results = evaluate(evaluator, List.of(EvalItem.of("Q", "A")));

        // Assert
        assertThat(results.counts()).isEqualTo(new EvalCounts(0, 1, 0));
        assertThat(results.items().getFirst().scores()).isEmpty();
        assertThat(results.allPassed()).isFalse();
    }

    @Test
    void evaluateAsync_shouldAwaitAsynchronousChecksInStableOrder() {
        // Arrange
        CompletableFuture<CheckResult> firstResult = new CompletableFuture<>();
        EvaluationCheck first = EvaluationCheck.asynchronous("first", (item, cancellation) -> firstResult);
        EvaluationCheck second = EvaluationCheck.synchronous("second", item -> CheckResult.pass("second done"));
        LocalEvaluator evaluator = new LocalEvaluator(first, second);

        // Act
        CompletionStage<EvalResults> stage =
                evaluator.evaluateAsync(List.of(EvalItem.of("Q", "A")), "async", new DefaultRunCancellation());
        assertThat(stage.toCompletableFuture()).isNotDone();
        firstResult.complete(CheckResult.pass("first done"));
        EvalResults results = stage.toCompletableFuture().join();

        // Assert
        assertThat(results.items().getFirst().scores())
                .extracting(EvalScoreResult::name)
                .containsExactly("first", "second");
    }

    @Test
    void evaluateAsync_shouldRecordCheckFailureAsItemErrorAndContinueOtherChecks() {
        // Arrange
        EvaluationCheck broken = EvaluationCheck.synchronous("broken", item -> {
            throw new IllegalStateException("boom");
        });
        EvaluationCheck healthy = EvaluationCheck.synchronous("healthy", item -> CheckResult.pass("ok"));
        LocalEvaluator evaluator = new LocalEvaluator(broken, healthy);

        // Act
        EvalResults results = evaluate(evaluator, List.of(EvalItem.of("Q", "A")));

        // Assert
        assertThat(results.counts()).isEqualTo(new EvalCounts(0, 0, 1));
        EvalItemResult item = results.items().getFirst();
        assertThat(item.status()).isEqualTo(EvalItemStatus.ERROR);
        assertThat(item.errorCode()).isEqualTo("check_error");
        assertThat(item.errorMessage()).contains("broken", "IllegalStateException", "boom");
        assertThat(item.scores()).extracting(EvalScoreResult::name).containsExactly("broken", "healthy");
        assertThat(results.perEvaluator())
                .containsEntry("broken", new EvalCounts(0, 0, 1))
                .containsEntry("healthy", new EvalCounts(1, 0, 0));
    }

    @Test
    void evaluateAsync_shouldPropagatePreRequestedCancellation() {
        // Arrange
        LocalEvaluator evaluator =
                new LocalEvaluator(EvaluationCheck.synchronous("pass", item -> CheckResult.pass("ok")));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        cancellation.cancel();

        // Act and assert
        assertThatThrownBy(() -> evaluator
                        .evaluateAsync(List.of(EvalItem.of("Q", "A")), "cancelled", cancellation)
                        .toCompletableFuture()
                        .join())
                .hasCauseInstanceOf(RunCancelledException.class);
    }

    @Test
    void evaluateAsync_shouldCancelPendingAsynchronousCheck() {
        // Arrange
        CompletableFuture<CheckResult> pending = new CompletableFuture<>();
        LocalEvaluator evaluator =
                new LocalEvaluator(EvaluationCheck.asynchronous("pending", (item, cancellation) -> pending));
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        CompletionStage<EvalResults> stage =
                evaluator.evaluateAsync(List.of(EvalItem.of("Q", "A")), "cancel", cancellation);

        // Act
        cancellation.cancel();

        // Assert
        assertThatThrownBy(() -> stage.toCompletableFuture().join()).hasCauseInstanceOf(RunCancelledException.class);
        assertThat(pending).isCancelled();
    }

    @Test
    void constructor_shouldRejectDuplicateOrBlankCheckNames() {
        // Arrange
        EvaluationCheck first = EvaluationCheck.synchronous("same", item -> CheckResult.pass("ok"));
        EvaluationCheck second = EvaluationCheck.synchronous("same", item -> CheckResult.pass("ok"));

        // Act and assert
        assertThatThrownBy(() -> new LocalEvaluator("local", List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> EvaluationCheck.synchronous(" ", item -> CheckResult.pass("ok")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void evaluateAsync_shouldRejectEmptyItemBatches() {
        // Arrange
        LocalEvaluator evaluator = new LocalEvaluator();

        // Act and assert
        assertThatThrownBy(() -> evaluator.evaluateAsync(List.of(), "empty", new DefaultRunCancellation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }

    private static EvalResults evaluate(LocalEvaluator evaluator, List<EvalItem> items) {
        return evaluator
                .evaluateAsync(items, "local test", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
    }
}
