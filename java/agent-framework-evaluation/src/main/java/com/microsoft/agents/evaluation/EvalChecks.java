// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provides deterministic provider-free checks for local evaluation.
 */
public final class EvalChecks {
    private EvalChecks() {}

    /**
     * Creates a case-insensitive check requiring every keyword in the response.
     *
     * @param keywords required keywords
     * @return keyword check
     */
    public static EvaluationCheck keyword(String... keywords) {
        return keyword(false, keywords);
    }

    /**
     * Creates a check requiring every keyword in the response.
     *
     * @param caseSensitive whether matching is case-sensitive
     * @param keywords required keywords
     * @return keyword check
     */
    public static EvaluationCheck keyword(boolean caseSensitive, String... keywords) {
        List<String> checkedKeywords = checkedDistinctNames(List.of(keywords), "keywords");
        return EvaluationCheck.synchronous("keyword", item -> {
            String response = caseSensitive ? item.response() : item.response().toLowerCase(Locale.ROOT);
            List<String> missing = checkedKeywords.stream()
                    .filter(keyword -> !response.contains(caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT)))
                    .toList();
            if (!missing.isEmpty()) {
                return CheckResult.fail("Missing keywords: " + String.join(", ", missing));
            }
            return CheckResult.pass("All keywords found: " + String.join(", ", checkedKeywords));
        });
    }

    /**
     * Creates a check requiring every named tool to have been called.
     *
     * @param toolNames exact tool names
     * @return tool-call check
     */
    public static EvaluationCheck toolCalled(String... toolNames) {
        return toolCalled(ToolCallMatchMode.ALL, toolNames);
    }

    /**
     * Creates a check requiring all or any named tools to have been called.
     *
     * @param mode matching mode
     * @param toolNames exact tool names
     * @return tool-call check
     */
    public static EvaluationCheck toolCalled(ToolCallMatchMode mode, String... toolNames) {
        ToolCallMatchMode checkedMode = Objects.requireNonNull(mode, "mode");
        List<String> checkedNames = checkedDistinctNames(List.of(toolNames), "toolNames");
        return EvaluationCheck.synchronous("tool_called", item -> {
            LinkedHashSet<String> called = toolCalls(item).stream()
                    .map(ActualToolCall::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> found = checkedNames.stream().filter(called::contains).toList();
            if (checkedMode == ToolCallMatchMode.ANY) {
                if (!found.isEmpty()) {
                    return CheckResult.pass("Called requested tools: " + String.join(", ", found));
                }
                return CheckResult.fail("None of the requested tools were called: " + String.join(", ", checkedNames)
                        + ". Called: " + String.join(", ", called));
            }
            List<String> missing =
                    checkedNames.stream().filter(name -> !called.contains(name)).toList();
            if (!missing.isEmpty()) {
                return CheckResult.fail(
                        "Missing tool calls: " + String.join(", ", missing) + ". Called: " + String.join(", ", called));
            }
            return CheckResult.pass("All requested tools were called: " + String.join(", ", checkedNames));
        });
    }

    /**
     * Creates a check requiring every expected tool-call name on the item to be present.
     *
     * <p>Matching is unordered, preserves multiplicity, and permits extra actual calls. Expected
     * arguments are intentionally ignored.
     *
     * @return expected-tool-presence check
     */
    public static EvaluationCheck toolCallsPresent() {
        return EvaluationCheck.synchronous("tool_calls_present", item -> {
            List<ExpectedToolCall> expected = item.expectedToolCalls();
            if (expected.isEmpty()) {
                return CheckResult.pass("No expected tool calls were specified.");
            }
            List<ActualToolCall> actual = toolCalls(item);
            boolean[] used = new boolean[actual.size()];
            List<String> missing = new ArrayList<>();
            for (ExpectedToolCall expectedCall : expected) {
                int match = findMatch(actual, used, expectedCall, false);
                if (match < 0) {
                    missing.add(expectedCall.name());
                } else {
                    used[match] = true;
                }
            }
            if (!missing.isEmpty()) {
                return CheckResult.fail("Missing expected tool calls: " + String.join(", ", missing) + ". Called: "
                        + actualNames(actual));
            }
            return CheckResult.pass("All expected tool calls were present.");
        });
    }

    /**
     * Creates a check requiring each expected tool call and argument subset to match.
     *
     * <p>Matching is unordered, preserves multiplicity, and permits extra actual calls and extra
     * actual argument members.
     *
     * @return expected-tool-argument check
     */
    public static EvaluationCheck toolCallArgsMatch() {
        return EvaluationCheck.synchronous("tool_call_args_match", item -> {
            List<ExpectedToolCall> expected = item.expectedToolCalls();
            if (expected.isEmpty()) {
                return CheckResult.pass("No expected tool calls were specified.");
            }
            List<ActualToolCall> actual = toolCalls(item);
            boolean[] used = new boolean[actual.size()];
            List<String> failures = new ArrayList<>();
            for (ExpectedToolCall expectedCall : expected) {
                int nameMatch = findMatch(actual, used, expectedCall, false);
                int argumentMatch = findMatch(actual, used, expectedCall, true);
                if (argumentMatch >= 0) {
                    used[argumentMatch] = true;
                } else if (nameMatch < 0) {
                    failures.add(expectedCall.name() + ": not called");
                } else {
                    failures.add(expectedCall.name() + ": arguments did not match");
                }
            }
            if (!failures.isEmpty()) {
                return CheckResult.fail("Tool-call argument mismatches: " + String.join("; ", failures));
            }
            return CheckResult.pass("All expected tool-call arguments matched.");
        });
    }

    private static List<String> checkedDistinctNames(List<String> names, String parameterName) {
        if (names.isEmpty()) {
            throw new IllegalArgumentException(parameterName + " must contain at least one value.");
        }
        List<String> checked = names.stream()
                .map(name -> EvaluationValidation.requireNonBlank(name, parameterName + " element"))
                .toList();
        if (new HashSet<>(checked).size() != checked.size()) {
            throw new IllegalArgumentException(parameterName + " must not contain duplicates.");
        }
        return checked;
    }

    private static List<ActualToolCall> toolCalls(EvalItem item) {
        List<ActualToolCall> calls = new ArrayList<>();
        item.conversation()
                .forEach(message -> message.contents().stream()
                        .filter(FunctionCallContent.class::isInstance)
                        .map(FunctionCallContent.class::cast)
                        .forEach(call -> calls.add(new ActualToolCall(call.name(), call.arguments()))));
        return List.copyOf(calls);
    }

    private static int findMatch(
            List<ActualToolCall> actual, boolean[] used, ExpectedToolCall expected, boolean checkArguments) {
        for (int index = 0; index < actual.size(); index++) {
            ActualToolCall candidate = actual.get(index);
            if (used[index] || !expected.name().equals(candidate.name())) {
                continue;
            }
            if (!checkArguments
                    || expected.arguments() == null
                    || argumentSubsetMatches(expected.arguments(), candidate.arguments())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean argumentSubsetMatches(StateValue.ObjectValue expected, StateValue actual) {
        if (!(actual instanceof StateValue.ObjectValue actualObject)) {
            return false;
        }
        for (Map.Entry<String, StateValue> member : expected.values().entrySet()) {
            StateValue actualValue = actualObject.values().get(member.getKey());
            if (actualValue == null || !stateValueEquals(member.getValue(), actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean stateValueEquals(StateValue expected, StateValue actual) {
        if (expected instanceof StateValue.NumberValue expectedNumber
                && actual instanceof StateValue.NumberValue actualNumber) {
            BigDecimal left = expectedNumber.value();
            BigDecimal right = actualNumber.value();
            return left.compareTo(right) == 0;
        }
        if (expected instanceof StateValue.ObjectValue expectedObject
                && actual instanceof StateValue.ObjectValue actualObject) {
            if (!expectedObject.values().keySet().equals(actualObject.values().keySet())) {
                return false;
            }
            return expectedObject.values().entrySet().stream()
                    .allMatch(entry -> stateValueEquals(
                            entry.getValue(), actualObject.values().get(entry.getKey())));
        }
        if (expected instanceof StateValue.ArrayValue expectedArray
                && actual instanceof StateValue.ArrayValue actualArray) {
            if (expectedArray.values().size() != actualArray.values().size()) {
                return false;
            }
            for (int index = 0; index < expectedArray.values().size(); index++) {
                if (!stateValueEquals(
                        expectedArray.values().get(index), actualArray.values().get(index))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static String actualNames(List<ActualToolCall> actual) {
        Set<String> names =
                actual.stream().map(ActualToolCall::name).collect(Collectors.toCollection(LinkedHashSet::new));
        return String.join(", ", names);
    }

    private record ActualToolCall(String name, StateValue arguments) {}
}
