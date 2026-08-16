// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalItemTest {
    @Test
    void builder_shouldDefensivelyCopyMutableInputs() {
        // Arrange
        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.text(Role.USER, "Question"));
        Map<String, StateValue> metadata = new java.util.LinkedHashMap<>();
        metadata.put("source", StateValue.string("test"));

        // Act
        EvalItem item = EvalItem.builder(conversation).metadata(metadata).build();
        conversation.add(Message.text(Role.ASSISTANT, "Late mutation"));
        metadata.put("other", StateValue.string("value"));

        // Assert
        assertThat(item.conversation()).hasSize(1);
        assertThat(item.metadata()).containsOnlyKeys("source");
        assertThatThrownBy(() -> item.conversation().add(Message.text(Role.ASSISTANT, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> item.metadata().put("x", StateValue.string("y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void split_shouldUseLastTurnByDefault() {
        // Arrange
        EvalItem item = EvalItem.builder(multiTurnConversation()).build();

        // Act
        ConversationSplit split = item.split();

        // Assert
        assertThat(split.queryMessages()).hasSize(5);
        assertThat(split.responseMessages()).hasSize(1);
        assertThat(item.query()).isEqualTo("Compare them.");
        assertThat(item.response()).isEqualTo("Seattle is cooler.");
    }

    @Test
    void fullSplitter_shouldIncludeSystemAndFirstUserMessageInQuery() {
        // Arrange
        List<Message> conversation = List.of(
                Message.text(Role.SYSTEM, "Be concise."),
                Message.text(Role.USER, "First"),
                Message.text(Role.ASSISTANT, "One"),
                Message.text(Role.USER, "Second"),
                Message.text(Role.ASSISTANT, "Two"));
        EvalItem item = EvalItem.builder(conversation)
                .splitter(ConversationSplitters.full())
                .build();

        // Act
        ConversationSplit split = item.split();

        // Assert
        assertThat(split.queryMessages()).containsExactly(conversation.get(0), conversation.get(1));
        assertThat(split.responseMessages()).containsExactlyElementsOf(conversation.subList(2, 5));
        assertThat(item.query()).isEqualTo("First");
        assertThat(item.response()).isEqualTo("One Two");
    }

    @Test
    void atMessageBoundary_shouldSplitAtExactIndex() {
        // Arrange
        List<Message> conversation = multiTurnConversation();

        // Act
        ConversationSplit split = ConversationSplitters.atMessageBoundary(2).split(conversation);

        // Assert
        assertThat(split.queryMessages()).containsExactlyElementsOf(conversation.subList(0, 2));
        assertThat(split.responseMessages()).containsExactlyElementsOf(conversation.subList(2, 6));
    }

    @Test
    void toolBoundarySplitters_shouldLocateCallAndResultMessages() {
        // Arrange
        List<Message> conversation = toolConversation();

        // Act
        ConversationSplit beforeName =
                ConversationSplitters.beforeToolCall("get_weather").split(conversation);
        ConversationSplit beforeId =
                ConversationSplitters.beforeToolCallId("call-1").split(conversation);
        ConversationSplit afterResult =
                ConversationSplitters.afterToolResult("call-1").split(conversation);

        // Assert
        assertThat(beforeName.queryMessages()).hasSize(1);
        assertThat(beforeName.responseMessages()).hasSize(3);
        assertThat(beforeId).isEqualTo(beforeName);
        assertThat(afterResult.queryMessages()).hasSize(3);
        assertThat(afterResult.responseMessages()).containsExactly(conversation.get(3));
    }

    @Test
    void toolBoundarySplitter_shouldFailWhenBoundaryIsAbsent() {
        // Arrange
        ConversationSplitter splitter = ConversationSplitters.beforeToolCall("missing");

        // Act and assert
        assertThatThrownBy(() -> splitter.split(multiTurnConversation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void split_shouldRejectCustomSplitterThatDropsMessages() {
        // Arrange
        EvalItem item = EvalItem.builder(multiTurnConversation()).build();
        ConversationSplitter invalid = conversation -> new ConversationSplit(conversation.subList(0, 1), List.of());

        // Act and assert
        assertThatThrownBy(() -> item.split(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preserve every message");
    }

    @Test
    void perTurnItems_shouldCreateCumulativeIndependentItems() {
        // Arrange
        List<Message> conversation = multiTurnConversation();

        // Act
        List<EvalItem> items = EvalItem.perTurnItems(conversation, List.of(), "shared context", Map.of());

        // Assert
        assertThat(items).hasSize(3);
        assertThat(items)
                .extracting(EvalItem::query)
                .containsExactly("Weather in Seattle?", "And Paris?", "Compare them.");
        assertThat(items)
                .extracting(EvalItem::response)
                .containsExactly("Seattle is cloudy.", "Paris is sunny.", "Seattle is cooler.");
        assertThat(items).extracting(item -> item.conversation().size()).containsExactly(2, 4, 6);
        assertThat(items).allMatch(item -> "shared context".equals(item.context()));
    }

    @Test
    void perTurnItems_shouldReturnEmptyWhenNoUserMessagesExist() {
        // Arrange
        List<Message> conversation =
                List.of(Message.text(Role.SYSTEM, "system"), Message.text(Role.ASSISTANT, "hello"));

        // Act
        List<EvalItem> items = EvalItem.perTurnItems(conversation);

        // Assert
        assertThat(items).isEmpty();
    }

    @Test
    void builder_shouldRejectEmptyConversationAndBlankContext() {
        // Act and assert
        assertThatThrownBy(() -> EvalItem.builder(List.of()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> EvalItem.builder(List.of(Message.text(Role.USER, "q")))
                        .context(" ")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context");
    }

    private static List<Message> multiTurnConversation() {
        return List.of(
                Message.text(Role.USER, "Weather in Seattle?"),
                Message.text(Role.ASSISTANT, "Seattle is cloudy."),
                Message.text(Role.USER, "And Paris?"),
                Message.text(Role.ASSISTANT, "Paris is sunny."),
                Message.text(Role.USER, "Compare them."),
                Message.text(Role.ASSISTANT, "Seattle is cooler."));
    }

    private static List<Message> toolConversation() {
        return List.of(
                Message.text(Role.USER, "Weather?"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new FunctionCallContent(
                                "call-1",
                                "get_weather",
                                StateValue.object(Map.of("city", StateValue.string("Seattle")))))),
                new Message(
                        Role.TOOL,
                        List.of(new FunctionResultContent(
                                "call-1", StateValue.object(Map.of("temperature", StateValue.integer(62)))))),
                Message.text(Role.ASSISTANT, "It is 62 degrees."));
    }
}
