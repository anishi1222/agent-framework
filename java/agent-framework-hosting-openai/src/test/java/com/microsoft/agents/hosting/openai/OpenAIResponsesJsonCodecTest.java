// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.hosting.HostingErrorCode;
import com.microsoft.agents.hosting.HostingException;
import com.microsoft.agents.hosting.HostingLimits;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAIResponsesJsonCodecTest {
    @Test
    void decodeRunRequest_shouldMapSupportedInputModelAndTools() {
        // Arrange
        AtomicReference<OpenAIResponsesRequestInfo> mappedInfo = new AtomicReference<>();
        OpenAIResponsesHostingOptions options = OpenAIResponsesHostingOptions.builder()
                .runOptionsMapper(info -> {
                    mappedInfo.set(info);
                    return RunOptions.builder()
                            .maxFunctionCalls(info.maxToolCalls())
                            .metadata(java.util.Map.of("application.setting", StateValue.string("accepted")))
                            .build();
                })
                .build();
        OpenAIResponsesJsonCodec codec = new OpenAIResponsesJsonCodec(HostingLimits.defaults(), options);
        byte[] request = utf8("""
                {
                  "input": [
                    {
                      "type": "message",
                      "id": "msg_user",
                      "role": "user",
                      "content": [
                        {"type": "input_text", "text": "weather"},
                        {
                          "type": "input_image",
                          "image_url": "https://example.test/map.png",
                          "detail": "low"
                        },
                        {
                          "type": "input_file",
                          "file_data": "data:text/plain;base64,SGVsbG8=",
                          "filename": "hello.txt",
                          "detail": "low"
                        }
                      ]
                    },
                    {
                      "type": "function_call",
                      "id": "fc_item",
                      "call_id": "call_1",
                      "name": "get_weather",
                      "arguments": "{\\"city\\":\\"Seattle\\"}"
                    },
                    {
                      "type": "function_call_output",
                      "call_id": "call_1",
                      "output": "{\\"temperature\\":18}"
                    },
                    {
                      "type": "reasoning",
                      "id": "reasoning_1",
                      "summary": [
                        {"type": "summary_text", "text": "Use the weather tool."}
                      ]
                    }
                  ],
                  "model": "gpt-test",
                  "instructions": "Be concise.",
                  "temperature": 0.2,
                  "top_p": 0.9,
                  "max_output_tokens": 64,
                  "parallel_tool_calls": false,
                  "max_tool_calls": 3,
                  "metadata": {"trace": "trace-1"},
                  "user": "user-1",
                  "tools": [
                    {
                      "type": "function",
                      "name": "get_weather",
                      "description": "Gets weather.",
                      "parameters": {
                        "type": "object",
                        "properties": {"city": {"type": "string"}}
                      },
                      "strict": true
                    }
                  ],
                  "tool_choice": {"type": "function", "name": "get_weather"}
                }
                """);

        // Act
        OpenAIResponsesRunRequest decoded = codec.decodeRunRequest(request);

        // Assert
        assertThat(mappedInfo.get().model()).isEqualTo("gpt-test");
        assertThat(mappedInfo.get().tools()).hasSize(1);
        assertThat(mappedInfo.get().metadata()).containsEntry("trace", "trace-1");
        assertThat(decoded.options().maxFunctionCalls()).isEqualTo(3);
        assertThat(decoded.options().metadata())
                .containsEntry("application.setting", StateValue.string("accepted"))
                .containsKey("openai.tools")
                .containsEntry("openai.model", StateValue.string("gpt-test"));
        assertThat(decoded.messages())
                .extracting(message -> message.role().value())
                .containsExactly("developer", "user", "assistant", "tool", "assistant");
        assertThat(decoded.messages().getFirst().text()).isEqualTo("Be concise.");

        Message user = decoded.messages().get(1);
        assertThat(user.messageId()).isEqualTo("msg_user");
        assertThat(user.contents()).hasSize(3);
        assertThat(user.contents().getFirst())
                .isInstanceOfSatisfying(
                        TextContent.class, text -> assertThat(text.text()).isEqualTo("weather"));
        assertThat(user.contents().get(1))
                .isInstanceOfSatisfying(
                        UriContent.class,
                        uri -> assertThat(uri.uri().toString()).isEqualTo("https://example.test/map.png"));
        assertThat(user.contents().get(2)).isInstanceOfSatisfying(DataContent.class, data -> {
            assertThat(new String(data.data(), StandardCharsets.UTF_8)).isEqualTo("Hello");
            assertThat(data.mediaType()).isEqualTo("text/plain");
        });

        assertThat(decoded.messages().get(2).contents().getFirst())
                .isInstanceOfSatisfying(FunctionCallContent.class, call -> {
                    assertThat(call.callId()).isEqualTo("call_1");
                    assertThat(call.name()).isEqualTo("get_weather");
                    assertThat(call.arguments()).isInstanceOf(StateValue.ObjectValue.class);
                });
        assertThat(decoded.messages().get(3).contents().getFirst()).isInstanceOf(FunctionResultContent.class);
        assertThat(decoded.messages().get(4).contents().getFirst())
                .isInstanceOfSatisfying(
                        ReasoningContent.class,
                        reasoning -> assertThat(reasoning.text()).isEqualTo("Use the weather tool."));
        assertThat(decoded.streaming()).isFalse();
        assertThat(decoded.store()).isTrue();
    }

    @Test
    void decodeRunRequest_shouldRejectMalformedDuplicateUnknownAndUnsafeUris() {
        // Arrange
        OpenAIResponsesJsonCodec codec =
                new OpenAIResponsesJsonCodec(HostingLimits.defaults(), OpenAIResponsesHostingOptions.defaults());
        List<String> invalidRequests = List.of(
                "{\"input\":\"hello\"",
                "{\"input\":\"one\",\"input\":\"two\"}",
                "{\"input\":\"hello\",\"unknown\":true}",
                """
                {
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [{
                      "type": "input_image",
                      "image_url": "../private.png"
                    }]
                  }]
                }
                """,
                """
                {
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [{
                      "type": "input_file",
                      "file_url": "file:///etc/passwd"
                    }]
                  }]
                }
                """,
                """
                {
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [{
                      "type": "input_image",
                      "image_url": "data:image/png;base64,not-base64!"
                    }]
                  }]
                }
                """,
                """
                {
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [{
                      "type": "input_image",
                      "image_url": "data:text/plain;base64,SGVsbG8="
                    }]
                  }]
                }
                """,
                """
                {
                  "input": [{
                    "type": "message",
                    "role": "user",
                    "content": [{
                      "type": "input_image",
                      "image_url": "https://example.test/image.png",
                      "detail": "maximum"
                    }]
                  }]
                }
                """);

        // Act / Assert
        for (String invalidRequest : invalidRequests) {
            HostingException failure =
                    assertThrows(HostingException.class, () -> codec.decodeRunRequest(utf8(invalidRequest)));
            assertThat(failure).isNotNull();
            assertThat(failure.error().code()).isEqualTo(HostingErrorCode.MALFORMED_REQUEST);
        }
    }

    @Test
    void decodeRunRequest_shouldEnforceRequestByteBound() {
        // Arrange
        HostingLimits limits = HostingLimits.builder()
                .maxRequestBytes(128)
                .maxWebSocketFrameBytes(128)
                .build();
        OpenAIResponsesJsonCodec codec = new OpenAIResponsesJsonCodec(limits, OpenAIResponsesHostingOptions.defaults());
        byte[] oversized = utf8("{\"input\":\"" + "x".repeat(256) + "\"}");

        // Act
        HostingException failure = assertThrows(HostingException.class, () -> codec.decodeRunRequest(oversized));

        // Assert
        assertThat(failure.error().code()).isEqualTo(HostingErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void defaultOptions_shouldRejectGenerationAndToolOverrides() {
        // Arrange
        OpenAIResponsesJsonCodec codec =
                new OpenAIResponsesJsonCodec(HostingLimits.defaults(), OpenAIResponsesHostingOptions.defaults());
        byte[] request = utf8("""
                {
                  "input": "hello",
                  "temperature": 0.4,
                  "tools": [{
                    "type": "function",
                    "name": "unsafe_override",
                    "parameters": {"type": "object"}
                  }]
                }
                """);

        // Act
        HostingException failure = assertThrows(HostingException.class, () -> codec.decodeRunRequest(request));

        // Assert
        assertThat(failure.error().code()).isEqualTo(HostingErrorCode.UNPROCESSABLE);
        assertThat(failure.error().details())
                .containsEntry("openaiCode", StateValue.string("unsupported_parameter"))
                .containsEntry("param", StateValue.string("temperature"));
    }

    @Test
    void encodeValue_shouldApplyGenericCredentialFieldRedaction() {
        // Arrange
        OpenAIResponsesJsonCodec codec =
                new OpenAIResponsesJsonCodec(HostingLimits.defaults(), OpenAIResponsesHostingOptions.defaults());
        StateValue value = StateValue.object(Map.of(
                "arguments",
                StateValue.object(Map.of(
                        "password", StateValue.string("tool-value"),
                        "token", StateValue.string("correlation-value")))));

        // Act
        String encoded = new String(codec.encodeValue(value), StandardCharsets.UTF_8);

        // Assert
        assertThat(encoded)
                .contains("\"password\":\"[REDACTED]\"", "\"token\":\"[REDACTED]\"")
                .doesNotContain("tool-value", "correlation-value");
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
