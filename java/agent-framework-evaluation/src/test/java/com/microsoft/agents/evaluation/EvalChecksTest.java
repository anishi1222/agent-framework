// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalChecksTest {
    @Test
    void keyword_shouldMatchAllKeywordsCaseInsensitivelyByDefault() {
        // Arrange
        EvaluationCheck check = EvalChecks.keyword("WEATHER", "sunny");

        // Act
        CheckResult result = run(check, EvalItem.of("Weather?", "The weather is SUNNY."));

        // Assert
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void keyword_shouldReportMissingKeywordsInInputOrder() {
        // Arrange
        EvaluationCheck check = EvalChecks.keyword("snow", "wind");

        // Act
        CheckResult result = run(check, EvalItem.of("Weather?", "The weather is sunny."));

        // Assert
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("snow, wind");
    }

    @Test
    void keyword_shouldHonorCaseSensitiveModeAndRejectDuplicates() {
        // Arrange
        EvaluationCheck check = EvalChecks.keyword(true, "SUNNY");

        // Act
        CheckResult result = run(check, EvalItem.of("Weather?", "sunny"));

        // Assert
        assertThat(result.passed()).isFalse();
        assertThatThrownBy(() -> EvalChecks.keyword("same", "same"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void toolCalled_shouldSupportAllAndAnyModes() {
        // Arrange
        EvalItem item = itemWithCalls(List.of(call("one", "tool_a", StateValue.object(Map.of()))), List.of());

        // Act
        CheckResult all = run(EvalChecks.toolCalled("tool_a", "tool_b"), item);
        CheckResult any = run(EvalChecks.toolCalled(ToolCallMatchMode.ANY, "tool_a", "tool_b"), item);

        // Assert
        assertThat(all.passed()).isFalse();
        assertThat(all.reason()).contains("tool_b");
        assertThat(any.passed()).isTrue();
    }

    @Test
    void toolCalled_shouldUseExactToolNames() {
        // Arrange
        EvalItem item = itemWithCalls(List.of(call("one", "Get_Weather", StateValue.object(Map.of()))), List.of());

        // Act
        CheckResult result = run(EvalChecks.toolCalled("get_weather"), item);

        // Assert
        assertThat(result.passed()).isFalse();
    }

    @Test
    void toolCallsPresent_shouldMatchExpectedNamesWithMultiplicityAndAllowExtras() {
        // Arrange
        EvalItem matching = itemWithCalls(
                List.of(
                        call("one", "lookup", StateValue.object(Map.of())),
                        call("two", "lookup", StateValue.object(Map.of())),
                        call("three", "extra", StateValue.object(Map.of()))),
                List.of(new ExpectedToolCall("lookup"), new ExpectedToolCall("lookup")));
        EvalItem missingDuplicate = itemWithCalls(
                List.of(call("one", "lookup", StateValue.object(Map.of()))),
                List.of(new ExpectedToolCall("lookup"), new ExpectedToolCall("lookup")));

        // Act
        CheckResult pass = run(EvalChecks.toolCallsPresent(), matching);
        CheckResult fail = run(EvalChecks.toolCallsPresent(), missingDuplicate);

        // Assert
        assertThat(pass.passed()).isTrue();
        assertThat(fail.passed()).isFalse();
        assertThat(fail.reason()).contains("lookup");
    }

    @Test
    void toolCallsPresent_shouldPassWhenNoExpectationsExist() {
        // Arrange
        EvalItem item = EvalItem.of("Hello", "World");

        // Act
        CheckResult result = run(EvalChecks.toolCallsPresent(), item);

        // Assert
        assertThat(result.passed()).isTrue();
        assertThat(result.reason()).contains("No expected");
    }

    @Test
    void toolCallArgsMatch_shouldUseRecursiveSubsetAndNumericSemanticEquality() {
        // Arrange
        StateValue.ObjectValue actualArguments = StateValue.object(Map.of(
                "location",
                StateValue.string("NYC"),
                "count",
                StateValue.number(new BigDecimal("1.0")),
                "nested",
                StateValue.object(Map.of(
                        "enabled", StateValue.bool(true),
                        "ignored", StateValue.string("extra"))),
                "extra",
                StateValue.string("allowed")));
        StateValue.ObjectValue expectedArguments = StateValue.object(Map.of(
                "location",
                StateValue.string("NYC"),
                "count",
                StateValue.integer(1),
                "nested",
                StateValue.object(Map.of(
                        "enabled", StateValue.bool(true),
                        "ignored", StateValue.string("extra")))));
        EvalItem item = itemWithCalls(
                List.of(call("one", "lookup", actualArguments)),
                List.of(new ExpectedToolCall("lookup", expectedArguments)));

        // Act
        CheckResult result = run(EvalChecks.toolCallArgsMatch(), item);

        // Assert
        assertThat(result.passed()).isTrue();
    }

    @Test
    void toolCallArgsMatch_shouldDistinguishMissingCallAndArgumentMismatch() {
        // Arrange
        EvalItem item = itemWithCalls(
                List.of(call("one", "lookup", StateValue.object(Map.of("location", StateValue.string("LA"))))),
                List.of(
                        new ExpectedToolCall("lookup", StateValue.object(Map.of("location", StateValue.string("NYC")))),
                        new ExpectedToolCall("other")));

        // Act
        CheckResult result = run(EvalChecks.toolCallArgsMatch(), item);

        // Assert
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("lookup: arguments did not match").contains("other: not called");
    }

    @Test
    void toolCallArgsMatch_shouldNotReuseOneActualCallForDuplicateExpectations() {
        // Arrange
        StateValue.ObjectValue arguments = StateValue.object(Map.of("value", StateValue.string("one")));
        EvalItem item = itemWithCalls(
                List.of(call("one", "lookup", arguments)),
                List.of(new ExpectedToolCall("lookup", arguments), new ExpectedToolCall("lookup", arguments)));

        // Act
        CheckResult result = run(EvalChecks.toolCallArgsMatch(), item);

        // Assert
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("lookup: not called");
    }

    private static CheckResult run(EvaluationCheck check, EvalItem item) {
        return check.evaluateAsync(item, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
    }

    private static FunctionCallContent call(String id, String name, StateValue arguments) {
        return new FunctionCallContent(id, name, arguments);
    }

    private static EvalItem itemWithCalls(List<FunctionCallContent> calls, List<ExpectedToolCall> expected) {
        Message callMessage = new Message(Role.ASSISTANT, calls);
        return EvalItem.builder(List.of(
                        Message.text(Role.USER, "Run tools"), callMessage, Message.text(Role.ASSISTANT, "Done")))
                .expectedToolCalls(expected)
                .build();
    }
}
