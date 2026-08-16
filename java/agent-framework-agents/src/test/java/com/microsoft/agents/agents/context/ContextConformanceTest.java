// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @Test
    void jcfContext001_shouldBindImmutableCompactionStrategiesAndHistorySafety() {
        // Arrange
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-CONTEXT-001");
        int keepLastToolGroups = integer(fixture.input(), "keepLastToolCallGroups");
        List<ToolGroup> toolGroups = ((ConformanceValue.ArrayValue)
                        fixture.input().require("toolGroups"))
                .values().stream()
                        .map(ConformanceValue.ObjectValue.class::cast)
                        .map(ContextConformanceTest::toolGroup)
                        .toList();
        ConformanceValue.ObjectValue contextWindow =
                (ConformanceValue.ObjectValue) fixture.input().require("contextWindow");
        List<Message> history = history(toolGroups);

        // Act
        CompactionResult selective = Compactions.compactAsync(
                        new SelectiveToolCallCompactionStrategy(keepLastToolGroups), history)
                .toCompletableFuture()
                .join();
        ToolResultCompactionStrategy toolStrategy = new ToolResultCompactionStrategy(keepLastToolGroups);
        CompactionResult toolFirst = Compactions.compactAsync(toolStrategy, history)
                .toCompletableFuture()
                .join();
        CompactionResult toolSecond = Compactions.compactAsync(toolStrategy, history)
                .toCompletableFuture()
                .join();
        TokenEstimator contextEstimator = message -> {
            if (message.text().startsWith("[Tool results:")) {
                return 1;
            }
            return message.messageId() != null && message.messageId().endsWith("-result") ? 20 : 1;
        };
        ContextWindowCompactionStrategy contextStrategy = new ContextWindowCompactionStrategy(
                integer(contextWindow, "maxContextWindowTokens"),
                integer(contextWindow, "maxOutputTokens"),
                decimal(contextWindow, "toolEvictionThreshold").doubleValue(),
                decimal(contextWindow, "truncationThreshold").doubleValue(),
                keepLastToolGroups);
        CompactionResult contextFirst = Compactions.compactAsync(
                        contextStrategy, history, contextEstimator, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        CompactionResult contextSecond = Compactions.compactAsync(
                        contextStrategy, history, contextEstimator, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        List<Message> atomicHistory = List.of(
                text(Role.USER, "old", "old"),
                call(toolGroups.getFirst(), "atomic-call"),
                result(toolGroups.getFirst(), "atomic-result"),
                text(Role.USER, "latest", "latest"));
        CompactionResult atomicFallback = Compactions.compactAsync(
                        new TokenBudgetComposedStrategy(1, List.of()),
                        atomicHistory,
                        message -> 1,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        List<Message> preamble = List.of(
                call(toolGroups.getFirst(), toolGroups.getFirst().callMessageId()),
                result(toolGroups.getFirst(), toolGroups.getFirst().resultMessageId()),
                text(Role.USER, "question", "preamble-user"),
                text(Role.ASSISTANT, "answer", "preamble-answer"));
        CompactionResult protectedPreamble = Compactions.compactAsync(
                        new SelectiveToolCallCompactionStrategy(0), preamble)
                .toCompletableFuture()
                .join();
        AtomicInteger firstStrategyCalls = new AtomicInteger();
        AtomicInteger secondStrategyCalls = new AtomicInteger();
        CompactionStrategy firstStrategy = request -> {
            firstStrategyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(CompactionSupport.projectedResult(
                    "first",
                    request,
                    List.of(request.messages().getLast()),
                    null,
                    CompactionLimitStatus.NOT_APPLICABLE));
        };
        CompactionStrategy secondStrategy = request -> {
            secondStrategyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    CompactionSupport.unchanged("second", request, CompactionSupport.groups(request), null));
        };
        Compactions.compactAsync(
                        new TokenBudgetComposedStrategy(1, List.of(firstStrategy, secondStrategy)),
                        List.of(text(Role.USER, "old", "early"), text(Role.ASSISTANT, "new", "late")),
                        message -> 1,
                        new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        // Assert
        List<String> toolSummaryTexts = toolFirst.messages().stream()
                .map(Message::text)
                .filter(text -> text.startsWith("[Tool results:"))
                .toList();
        List<String> generatedSummaryIds = toolFirst.messages().stream()
                .filter(message -> message.text().startsWith("[Tool results:"))
                .map(Message::messageId)
                .toList();
        List<String> contextCallIds = contextFirst.messages().stream()
                .flatMap(message -> message.contents().stream())
                .filter(FunctionCallContent.class::isInstance)
                .map(FunctionCallContent.class::cast)
                .map(FunctionCallContent::callId)
                .toList();
        long contextSummaryCount = contextFirst.messages().stream()
                .filter(message -> message.text().startsWith("[Tool results:"))
                .count();
        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "selectiveMessageIds",
                strings(selective.messages().stream().map(Message::messageId).toList()));
        actual.put("toolSummaryTexts", strings(toolSummaryTexts));
        actual.put("toolSummarySourceIds", strings(toolFirst.audit().summarizedMessageIds()));
        actual.put(
                "contextWindowSummaryCount", new ConformanceValue.NumberValue(BigDecimal.valueOf(contextSummaryCount)));
        actual.put("contextWindowRetainedCallIds", strings(contextCallIds));
        actual.put(
                "atomicFallbackMessageIds",
                strings(atomicFallback.messages().stream()
                        .map(Message::messageId)
                        .toList()));
        actual.put(
                "multipleSummaryIdsExposed",
                new ConformanceValue.BooleanValue(toolFirst.audit().summaryMessageId() == null
                        && toolFirst.audit().summaryMessageIds().equals(generatedSummaryIds)));
        actual.put(
                "resolvedPreambleProtected",
                new ConformanceValue.BooleanValue(protectedPreamble.messages().equals(preamble)));
        actual.put(
                "composedEarlyStop",
                new ConformanceValue.BooleanValue(firstStrategyCalls.get() == 1 && secondStrategyCalls.get() == 0));
        actual.put(
                "deterministic",
                new ConformanceValue.BooleanValue(toolFirst.equals(toolSecond) && contextFirst.equals(contextSecond)));
        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private static List<Message> history(List<ToolGroup> groups) {
        ArrayList<Message> messages = new ArrayList<>();
        messages.add(text(Role.USER, "question", "user"));
        groups.forEach(group -> {
            messages.add(call(group, group.callMessageId()));
            messages.add(result(group, group.resultMessageId()));
        });
        messages.add(text(Role.ASSISTANT, "done", "done"));
        return List.copyOf(messages);
    }

    private static Message call(ToolGroup group, String messageId) {
        return Message.builder(Role.ASSISTANT)
                .contents(List.of(new FunctionCallContent(group.callId(), group.name(), StateValue.object(Map.of()))))
                .messageId(messageId)
                .build();
    }

    private static Message result(ToolGroup group, String messageId) {
        return Message.builder(Role.TOOL)
                .contents(List.of(new FunctionResultContent(group.callId(), StateValue.string(group.result()))))
                .messageId(messageId)
                .build();
    }

    private static Message text(Role role, String value, String messageId) {
        return Message.builder(role)
                .contents(List.of(new com.microsoft.agents.core.TextContent(value)))
                .messageId(messageId)
                .build();
    }

    private static ToolGroup toolGroup(ConformanceValue.ObjectValue value) {
        return new ToolGroup(
                string(value, "callId"),
                string(value, "name"),
                string(value, "result"),
                string(value, "callMessageId"),
                string(value, "resultMessageId"));
    }

    private static String string(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.StringValue) value.require(name)).value();
    }

    private static int integer(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.NumberValue) value.require(name)).value().intValueExact();
    }

    private static BigDecimal decimal(ConformanceValue.ObjectValue value, String name) {
        return ((ConformanceValue.NumberValue) value.require(name)).value();
    }

    private static ConformanceValue.ArrayValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private record ToolGroup(String callId, String name, String result, String callMessageId, String resultMessageId) {}
}
