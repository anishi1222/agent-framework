// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MetadataAndResponseExtensionsTest {
    @Test
    void metadataValues_shouldProvideImmutableTypedOperationsAndTypeNamedKeys() {
        // Arrange
        MetadataKey<String> name = MetadataKey.string("name");
        MetadataKey<BigInteger> count = MetadataKey.integer("count");
        MetadataKey<String> typed = MetadataKey.forType(
                MetadataAndResponseExtensionsTest.class,
                StateValue::string,
                value -> ((StateValue.StringValue) value).value());
        AtomicInteger encodes = new AtomicInteger();
        MetadataKey<String> lazy = MetadataKey.forType(
                AtomicInteger.class,
                value -> {
                    encodes.incrementAndGet();
                    return StateValue.string(value);
                },
                value -> ((StateValue.StringValue) value).value());
        Map<String, StateValue> original = Map.of("existing", StateValue.bool(true));

        // Act
        Map<String, StateValue> withName = MetadataValues.with(original, name, "agent");
        Map<String, StateValue> withCount = MetadataValues.with(withName, count, BigInteger.valueOf(2));
        Map<String, StateValue> unchanged = MetadataValues.withIfAbsent(withCount, count, BigInteger.valueOf(9));
        Map<String, StateValue> withTyped = MetadataValues.with(unchanged, typed, "typed");
        MetadataValues.withIfAbsent(Map.of(lazy.name(), StateValue.string("existing")), lazy, "new");
        Map<String, StateValue> removed = MetadataValues.without(withTyped, name);

        // Assert
        assertThat(original).containsOnlyKeys("existing");
        assertThat(MetadataValues.find(withCount, name)).contains("agent");
        assertThat(MetadataValues.find(withCount, count)).contains(BigInteger.valueOf(2));
        assertThat(MetadataValues.find(unchanged, count)).contains(BigInteger.valueOf(2));
        assertThat(typed.name()).isEqualTo(MetadataAndResponseExtensionsTest.class.getName());
        assertThat(MetadataValues.contains(withTyped, typed)).isTrue();
        assertThat(MetadataValues.find(withTyped, typed)).contains("typed");
        assertThat(encodes).hasValue(0);
        assertThat(removed).doesNotContainKey("name");
        assertThatThrownBy(() -> MetadataValues.find(Map.of("count", StateValue.string("wrong")), count))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void agentResponses_shouldConvertSharedFieldsAndAggregateUpdates() {
        // Arrange
        Instant createdAt = Instant.parse("2026-08-13T00:00:00Z");
        UsageDetails usage =
                UsageDetails.builder().inputTokens(1).totalTokens(1).build();
        AgentResponse<String> response = AgentResponse.<String>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                .responseId("response-1")
                .agentId("agent-1")
                .createdAt(createdAt)
                .finishReason(FinishReason.STOP)
                .usage(usage)
                .value("structured")
                .continuationToken(StateValue.string("continue"))
                .metadata(Map.of("key", StateValue.string("value")))
                .updateSequences(List.of(1L))
                .build();
        AgentResponseUpdate update = AgentResponseUpdate.builder()
                .sequence(1)
                .contents(List.of(new TextContent("done")))
                .role(Role.ASSISTANT)
                .agentId("agent-1")
                .responseId("response-1")
                .messageId("message-1")
                .createdAt(createdAt)
                .finishReason(FinishReason.STOP)
                .usage(usage)
                .metadata(Map.of("key", StateValue.string("value")))
                .build();

        // Act
        ChatResponse chat = AgentResponses.toChatResponse(response);
        ChatResponseUpdate chatUpdate = AgentResponses.toChatResponseUpdate(update);
        AgentResponse<Void> aggregated = AgentResponses.aggregate(List.of(update));

        // Assert
        assertThat(chat.text()).isEqualTo("done");
        assertThat(chat.responseId()).isEqualTo("response-1");
        assertThat(chat.createdAt()).isEqualTo(createdAt);
        assertThat(chat.usage()).isSameAs(usage);
        assertThat(chat.metadata()).containsEntry("key", StateValue.string("value"));
        assertThat(chatUpdate.text()).isEqualTo("done");
        assertThat(chatUpdate.messageId()).isEqualTo("message-1");
        assertThat(aggregated.agentId()).isEqualTo("agent-1");
        assertThat(aggregated.text()).isEqualTo("done");
    }
}
