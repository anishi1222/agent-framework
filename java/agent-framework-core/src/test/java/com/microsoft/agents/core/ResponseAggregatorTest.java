// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseAggregatorTest {
    @Test
    void chatAggregation_shouldPreserveOrderingGroupingMetadataAndTerminalFields() {
        // Arrange
        ResponseAggregator.ChatAggregation aggregation = ResponseAggregator.chat();

        // Act
        aggregation.add(ChatResponseUpdate.builder()
                .sequence(2)
                .role(Role.ASSISTANT)
                .responseId("response-1")
                .messageId("message-1")
                .contents(List.of(new TextContent("hello ")))
                .metadata(Map.of("same", StateValue.string("first"), "left", StateValue.integer(1)))
                .build());
        aggregation.add(ChatResponseUpdate.builder()
                .sequence(1)
                .responseId("response-1")
                .messageId("message-1")
                .contents(List.of(new TextContent("world")))
                .metadata(Map.of("same", StateValue.string("last")))
                .build());
        aggregation.add(ChatResponseUpdate.builder()
                .sequence(3)
                .role(Role.TOOL)
                .responseId("response-1")
                .messageId("message-2")
                .contents(List.of(new FunctionResultContent("call-1", StateValue.string("done"))))
                .finishReason(FinishReason.STOP)
                .build());
        ChatResponse response = aggregation.response();

        // Assert
        assertThat(response.messages()).hasSize(2);
        assertThat(response.messages().get(0).text()).isEqualTo("hello world");
        assertThat(response.messages().get(1).role()).isEqualTo(Role.TOOL);
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.updateSequences()).containsExactly(2L, 1L, 3L);
        assertThat(response.metadata())
                .containsEntry("same", StateValue.string("last"))
                .containsEntry("left", StateValue.integer(1));
        assertThat(aggregation.isTerminal()).isTrue();
    }

    @Test
    void aggregation_shouldApplyPythonCompatibleSequentialUsageFoldsWithoutOverflow() {
        // Arrange
        UsageDetails first = new UsageDetails(Map.of(
                "inputTokens", StateValue.integer(2),
                "outputTokens", StateValue.nullValue(),
                "alwaysDropped", StateValue.integer(1),
                "reintroduced", StateValue.integer(1),
                "scaledNumber", StateValue.number(new BigDecimal("1.0")),
                "largeTokens", StateValue.integer(new BigInteger("9223372036854775808"))));
        UsageDetails second = new UsageDetails(Map.of(
                "inputTokens", StateValue.integer(3),
                "outputTokens", StateValue.integer(2),
                "alwaysDropped", StateValue.string("non-numeric"),
                "reintroduced", StateValue.string("non-numeric"),
                "largeTokens", StateValue.integer(2)));
        UsageDetails third = new UsageDetails(Map.of(
                "inputTokens", StateValue.integer(1),
                "reintroduced", StateValue.integer(7),
                "scaledNumber", StateValue.integer(9),
                "fractional", StateValue.number(new BigDecimal("1.5")),
                "largeTokens", StateValue.integer(1)));

        // Act
        AgentResponse<Void> response = ResponseAggregator.<Void>agent()
                .add(AgentResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .usage(first)
                        .build())
                .add(AgentResponseUpdate.builder().usage(second).build())
                .add(AgentResponseUpdate.builder().usage(third).build())
                .add(AgentResponseUpdate.builder()
                        .usage(UsageDetails.of("inputTokens", 4))
                        .finishReason(FinishReason.STOP)
                        .build())
                .response();

        // Assert
        assertThat(response.usage().integer("inputTokens")).contains(BigInteger.TEN);
        assertThat(response.usage().integer("outputTokens")).contains(BigInteger.valueOf(2));
        assertThat(response.usage().integer("reintroduced")).contains(BigInteger.valueOf(7));
        assertThat(response.usage().integer("scaledNumber")).contains(BigInteger.valueOf(9));
        assertThat(response.usage().integer("largeTokens")).contains(new BigInteger("9223372036854775811"));
        assertThat(response.usage().values()).doesNotContainKeys("alwaysDropped", "fractional");
    }

    @Test
    void aggregation_shouldMergeCompatibleFunctionCallDeltas() {
        AgentResponse<Void> response = ResponseAggregator.<Void>agent()
                .add(AgentResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(
                                List.of(new FunctionCallContent("call-1", "inspect", StateValue.string("{\"value\":"))))
                        .build())
                .add(AgentResponseUpdate.builder()
                        .contents(List.of(new FunctionCallContent("call-1", "inspect", StateValue.string("7}"))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build())
                .response();

        assertThat(response.messages()).singleElement().satisfies(message -> {
            assertThat(message.contents())
                    .singleElement()
                    .isInstanceOfSatisfying(
                            FunctionCallContent.class,
                            call -> assertThat(call.arguments()).isEqualTo(StateValue.string("{\"value\":7}")));
        });
    }

    @Test
    void incompatibleUpdate_shouldFailExplicitly_andLeavePriorStateUnchanged() {
        // Arrange
        AgentResponseUpdate accepted = AgentResponseUpdate.builder()
                .responseId("response-1")
                .role(Role.ASSISTANT)
                .authorName("original")
                .contents(List.of(new FunctionCallContent(
                        "call-1", "inspect", StateValue.object(Map.of("a", StateValue.integer(1))))))
                .metadata(Map.of("accepted", StateValue.bool(true)))
                .build();
        AgentResponse<Void> expected =
                ResponseAggregator.<Void>agent().add(accepted).finish();
        ResponseAggregator.AgentAggregation<Void> aggregation =
                ResponseAggregator.<Void>agent().add(accepted);

        // Act / Assert
        assertThatThrownBy(() -> aggregation.add(AgentResponseUpdate.builder()
                        .responseId("response-1")
                        .authorName("rejected")
                        .contents(List.of(new FunctionCallContent("call-2", "inspect", StateValue.object(Map.of()))))
                        .metadata(Map.of("rejected", StateValue.bool(true)))
                        .continuationToken(StateValue.string("rejected-token"))
                        .build()))
                .isInstanceOf(ValidationException.class);

        assertThat(aggregation.finish()).isEqualTo(expected);
    }

    @Test
    void aggregation_shouldInspectAtMostOneExistingContentItemPerUpdate() {
        // Arrange
        ResponseAggregator.AgentAggregation<Void> aggregation = ResponseAggregator.agent();
        int updateCount = 1_000;

        // Act
        for (int index = 0; index < updateCount; index++) {
            aggregation.add(AgentResponseUpdate.builder()
                    .contents(List.of(new MetadataContent(Map.of("index", StateValue.integer(index)))))
                    .build());
        }
        AgentResponse<Void> response = aggregation.finish();

        // Assert
        assertThat(response.messages().getFirst().contents()).hasSize(updateCount);
        assertThat(aggregation.inspectedExistingContentItemsForTesting()).isEqualTo(updateCount - 1L);
    }

    @Test
    void tokenlessUpdates_shouldPreserveLastNonNullContinuationToken() {
        // Arrange
        StateValue token = StateValue.object(Map.of("cursor", StateValue.string("next")));

        // Act
        ChatResponse chat = ResponseAggregator.chat()
                .add(ChatResponseUpdate.builder().continuationToken(token).build())
                .add(ChatResponseUpdate.builder()
                        .finishReason(FinishReason.STOP)
                        .build())
                .response();
        AgentResponse<Void> agent = ResponseAggregator.<Void>agent()
                .add(AgentResponseUpdate.builder().continuationToken(token).build())
                .add(AgentResponseUpdate.builder()
                        .finishReason(FinishReason.STOP)
                        .build())
                .response();

        // Assert
        assertThat(chat.continuationToken()).isEqualTo(token);
        assertThat(agent.continuationToken()).isEqualTo(token);
    }

    @Test
    void terminalAggregation_shouldRejectLaterUpdates_andFinishIdempotently() {
        ResponseAggregator.ChatAggregation aggregation = ResponseAggregator.chat()
                .add(ChatResponseUpdate.builder()
                        .finishReason(FinishReason.STOP)
                        .build());

        ChatResponse first = aggregation.finish();
        ChatResponse second = aggregation.finish();

        assertThat(first).isEqualTo(second);
        assertThatThrownBy(() -> aggregation.add(ChatResponseUpdate.builder().build()))
                .isInstanceOf(IllegalStateException.class);
    }
}
