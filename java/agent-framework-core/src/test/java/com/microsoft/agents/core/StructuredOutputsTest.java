// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredOutputsTest {
    @Test
    void decode_shouldUseLastNonEmptyAssistantTextAndPreserveResponseMetadata() {
        // Arrange
        AgentResponse<Void> source = AgentResponse.<Void>builder()
                .messages(List.of(
                        Message.text(Role.ASSISTANT, "{\"ignored\":true}"),
                        Message.text(Role.USER, "later input"),
                        Message.text(Role.ASSISTANT, " "),
                        new Message(
                                Role.ASSISTANT,
                                List.of(
                                        new TextContent("{\"answer\":"),
                                        new ReasoningContent("reasoning", "not part of JSON"),
                                        new TextContent("42}")))))
                .responseId("response-1")
                .agentId("agent-1")
                .finishReason(FinishReason.STOP)
                .metadata(Map.of("safe", StateValue.bool(true)))
                .updateSequences(List.of(0L, 1L))
                .build();

        // Act
        AgentResponse<Integer> decoded = StructuredOutputs.decode(
                source,
                value -> Math.toIntExact(((StateValue.NumberValue) ((StateValue.ObjectValue) value).require("answer"))
                        .value()
                        .longValueExact()));

        // Assert
        assertThat(decoded.value()).isEqualTo(42);
        assertThat(decoded.messages()).isEqualTo(source.messages());
        assertThat(decoded.responseId()).isEqualTo("response-1");
        assertThat(decoded.agentId()).isEqualTo("agent-1");
        assertThat(decoded.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(decoded.metadata()).isEqualTo(source.metadata());
        assertThat(decoded.updateSequences()).isEqualTo(source.updateSequences());
    }

    @Test
    void decode_shouldReturnNullValueWhenAssistantHasNoText() {
        // Arrange
        AgentResponse<Void> source = AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.USER, "only input")))
                .build();

        // Act
        AgentResponse<StateValue> decoded = StructuredOutputs.decode(source, StructuredOutputDecoder.stateValue());

        // Assert
        assertThat(decoded.value()).isNull();
    }

    @Test
    void decode_shouldRejectMalformedOrUndecodableOutputWithoutEchoingContent() {
        // Arrange
        AgentResponse<Void> malformed = AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "{\"secret\":\"credential\"")))
                .build();
        AgentResponse<Void> undecodable = AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "{\"secret\":\"credential\"}")))
                .build();
        StructuredOutputException direct = new StructuredOutputException("direct decoder failure", null);

        // Act and assert
        assertThatThrownBy(() -> StructuredOutputs.decode(malformed, StructuredOutputDecoder.stateValue()))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageNotContaining("credential");
        assertThatThrownBy(() -> StructuredOutputs.decode(undecodable, value -> {
                    throw new IllegalArgumentException("application decoder failed");
                }))
                .isInstanceOf(StructuredOutputException.class)
                .hasMessageNotContaining("credential")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StructuredOutputs.decode(undecodable, value -> {
                    throw direct;
                }))
                .isSameAs(direct);
    }

    @Test
    void parseJson_shouldEnforceExplicitDocumentLimit() {
        // Arrange
        SerializationLimits limits = new SerializationLimits(4, 8, 16, 8, 8);

        // Act and assert
        assertThatThrownBy(() -> StructuredOutputs.parseJson("{\"a\":1}", limits))
                .isInstanceOf(SerializationException.class)
                .extracting("error")
                .isEqualTo(SerializationError.DOCUMENT_BYTES);
    }
}
