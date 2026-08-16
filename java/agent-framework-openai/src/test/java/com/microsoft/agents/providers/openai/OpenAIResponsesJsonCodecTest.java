// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIResponsesJsonCodecTest {
    @Test
    void codec_shouldRoundTripMappedTextAndFunctionProtocolValues() {
        // Arrange
        OpenAITransport.Request request = new OpenAITransport.Request(
                "deployment",
                List.of(
                        new OpenAITransport.MessageInput(
                                OpenAITransport.InputRole.USER, List.of(new OpenAITransport.TextInput("hello"))),
                        new OpenAITransport.FunctionResultInput("call-1", StateValue.string("sunny"), List.of(), null)),
                "help",
                0.2,
                0.8,
                100L,
                List.of(new OpenAITransport.FunctionTool(
                        "weather", "Gets weather", StateValue.object(Map.of("type", StateValue.string("object"))))),
                OpenAITransport.ToolSelection.AUTO,
                true,
                null,
                true,
                null,
                "conv-1",
                Map.of("tenant", "one"),
                StructuredOutputOptions.builder()
                        .name("weather_response")
                        .description("Weather response")
                        .schema(StateValue.object(Map.of("type", StateValue.string("object"))))
                        .strict(true)
                        .build(),
                OpenAIResponseOptions.builder().includeEncryptedReasoning(false).build());

        // Act
        String json = OpenAIResponsesJsonCodec.encodeRequest(request);
        OpenAITransport.Response response = OpenAIResponsesJsonCodec.decodeResponse("""
                {
                  "id":"resp-1",
                  "conversation":{"id":"conv-1"},
                  "model":"deployment",
                  "created_at":1,
                  "status":"completed",
                  "output":[
                    {"type":"message","id":"msg-1","content":[{"type":"output_text","text":"done"}]},
                    {"type":"function_call","id":"item-1","call_id":"call-2","name":"weather",
                     "arguments":"{\\"city\\":\\"Paris\\"}","status":"completed"}
                  ],
                  "usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}
                }
                """, "request-1");

        // Assert
        assertThat(json)
                .contains("\"conversation\":\"conv-1\"")
                .contains("\"function_call_output\"")
                .contains("\"parallel_tool_calls\":true")
                .contains("\"type\":\"json_schema\"")
                .contains("\"name\":\"weather_response\"");
        assertThat(response.responseId()).isEqualTo("resp-1");
        assertThat(response.conversationId()).isEqualTo("conv-1");
        assertThat(response.createdAt()).isEqualTo(Instant.ofEpochSecond(1));
        assertThat(response.outputs()).hasSize(2);
        assertThat(response.usage().totalTokens()).isEqualTo(5);
    }

    @Test
    void streamCodec_shouldMapTextFunctionAndTerminalEvents() {
        // Act
        List<OpenAITransport.StreamEvent> started = OpenAIResponsesJsonCodec.decodeStreamEvent("""
                {"type":"response.created","sequence_number":0,
                 "response":{"id":"resp-1","model":"deployment","created_at":1,"status":"in_progress"}}
                """, "request-1", null);
        List<OpenAITransport.StreamEvent> call = OpenAIResponsesJsonCodec.decodeStreamEvent("""
                {"type":"response.output_item.done","sequence_number":2,"output_index":0,
                 "item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"weather",
                         "arguments":"{\\"city\\":\\"Paris\\"}"}}
                """, "request-1", null);
        List<OpenAITransport.StreamEvent> completed =
                OpenAIResponsesJsonCodec.decodeStreamEvent("""
                {"type":"response.completed","sequence_number":3,
                 "response":{"id":"resp-1","model":"deployment","created_at":1,"status":"completed",
                             "output":[]}}
                """, "request-1", null);

        // Assert
        assertThat(started).singleElement().isInstanceOf(OpenAITransport.ResponseStarted.class);
        assertThat(call).singleElement().isInstanceOf(OpenAITransport.FunctionArgumentsDone.class);
        assertThat(completed).singleElement().isInstanceOf(OpenAITransport.ResponseCompleted.class);
    }

    @Test
    void streamCodec_shouldRejectUnknownObservableEvents() {
        assertThatThrownBy(() -> OpenAIResponsesJsonCodec.decodeStreamEvent(
                        "{\"type\":\"response.computer_call.in_progress\"}", null, "deployment"))
                .isInstanceOf(OpenAIProtocolException.class)
                .hasMessageContaining("unsupported");
    }
}
