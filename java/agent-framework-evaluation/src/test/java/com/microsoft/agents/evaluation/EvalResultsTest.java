// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalResultsTest {
    @Test
    void allPassed_shouldRequireCompletedNonEmptyEvidence() {
        // Arrange
        EvalResults passing = results(EvalRunStatus.COMPLETED, new EvalCounts(1, 0, 0));
        EvalResults empty = results(EvalRunStatus.COMPLETED, new EvalCounts(0, 0, 0));
        EvalResults failed = results(EvalRunStatus.COMPLETED, new EvalCounts(1, 1, 0));
        EvalResults cancelled = results(EvalRunStatus.CANCELLED, new EvalCounts(1, 0, 0));

        // Act and assert
        assertThat(passing.allPassed()).isTrue();
        assertThat(empty.allPassed()).isFalse();
        assertThat(failed.allPassed()).isFalse();
        assertThat(cancelled.allPassed()).isFalse();
    }

    @Test
    void allPassed_shouldIncludeNestedResults() {
        // Arrange
        EvalResults passingChild = results(EvalRunStatus.COMPLETED, new EvalCounts(1, 0, 0));
        EvalResults failingChild = results(EvalRunStatus.COMPLETED, new EvalCounts(0, 1, 0));
        EvalResults parent = new EvalResults(
                "parent",
                "parent evaluation",
                null,
                null,
                EvalRunStatus.COMPLETED,
                new EvalCounts(1, 0, 0),
                Map.of(),
                List.of(),
                Map.of("passing", passingChild, "failing", failingChild),
                null);

        // Act and assert
        assertThat(parent.allPassed()).isFalse();
        assertThatThrownBy(parent::assertPassed)
                .isInstanceOf(EvalNotPassedException.class)
                .hasMessageContaining("did not pass");
    }

    @Test
    void assertPassed_shouldNotThrowForPassingResults() {
        // Arrange
        EvalResults results = results(EvalRunStatus.COMPLETED, new EvalCounts(2, 0, 0));

        // Act and assert
        assertThatCode(results::assertPassed).doesNotThrowAnyException();
    }

    @Test
    void contracts_shouldRejectInvalidCountsScoresAndStatuses() {
        // Act and assert
        assertThatThrownBy(() -> new EvalCounts(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalScoreResult("score", Double.NaN, true, "reason"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalItemResult(
                        "item", EvalItemStatus.ERROR, List.of(), "input", "output", "code", null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorMessage");
        assertThatThrownBy(() -> new EvalItemResult(
                        "item", EvalItemStatus.PASS, List.of(), "input", "output", null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passing item");
    }

    private static EvalResults results(EvalRunStatus status, EvalCounts counts) {
        return new EvalResults(
                "test", "test evaluation", null, null, status, counts, Map.of(), List.of(), Map.of(), null);
    }
}
