// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolMode;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallArgumentsDoneEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseStreamEvent;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings("deprecation")
class OpenAISdkMapperTest {
    @Test
    void sdkRequestMapper_shouldMapOfficialResponsesTypesAndProviderOptions() {
        // Arrange
        OpenAIResponseOptions responseOptions = OpenAIResponseOptions.builder()
                .reasoningEffort(OpenAIReasoningEffort.HIGH)
                .reasoningSummary(OpenAIReasoningSummary.CONCISE)
                .serviceTier(OpenAIServiceTier.FLEX)
                .truncation(OpenAITruncation.AUTO)
                .imageOutputFormat(OpenAIImageOutputFormat.WEBP)
                .background(true)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .temperature(0.2)
                        .topP(0.8)
                        .maxTokens(99)
                        .allowMultipleToolCalls(true)
                        .metadata(Map.of("tenant", StateValue.string("contoso")))
                        .build(),
                List.of(OpenAIRequestMapperTest.functionTool()),
                ToolMode.AUTO,
                null);
        OpenAITransport.Request boundary = OpenAIRequestMapper.map(
                request, OpenAIChatClientOptions.builder().model("gpt-test").build(), responseOptions);

        // Act
        ResponseCreateParams sdk = OpenAISdkRequestMapper.map(boundary);

        // Assert
        assertThat(sdk.model().orElseThrow().asString()).isEqualTo("gpt-test");
        assertThat(sdk.input().orElseThrow().asResponse()).hasSize(1);
        assertThat(sdk.input()
                        .orElseThrow()
                        .asResponse()
                        .getFirst()
                        .asEasyInputMessage()
                        .role()
                        .asString())
                .isEqualTo("user");
        assertThat(sdk.tools().orElseThrow().getFirst().asFunction().name()).isEqualTo("lookup");
        assertThat(sdk.tools().orElseThrow().get(1).asImageGeneration().outputFormat())
                .contains(com.openai.models.responses.Tool.ImageGeneration.OutputFormat.WEBP);
        assertThat(sdk.toolChoice().orElseThrow().asOptions().asString()).isEqualTo("auto");
        assertThat(sdk.reasoning().orElseThrow().effort().orElseThrow().asString())
                .isEqualTo("high");
        assertThat(sdk.reasoning().orElseThrow().summary().orElseThrow().asString())
                .isEqualTo("concise");
        assertThat(sdk.serviceTier().orElseThrow().asString()).isEqualTo("flex");
        assertThat(sdk.truncation().orElseThrow().asString()).isEqualTo("auto");
        assertThat(sdk.include().orElseThrow())
                .extracting(com.openai.models.responses.ResponseIncludable::asString)
                .containsExactly("reasoning.encrypted_content");
        assertThat(sdk.metadata()
                        .orElseThrow()
                        ._additionalProperties()
                        .get("tenant")
                        .convert(String.class))
                .isEqualTo("contoso");
    }

    @Test
    void sdkRequestMapper_shouldPreserveFunctionCallRoundTripItems() {
        // Arrange
        StateValue arguments = StateValue.object(Map.of("city", StateValue.string("Paris")));
        ChatClientRequest request = new ChatClientRequest(
                List.of(
                        Message.text(Role.USER, "weather"),
                        new Message(
                                Role.ASSISTANT,
                                List.of(new FunctionCallContent(
                                        "call-1",
                                        "lookup",
                                        arguments,
                                        false,
                                        Map.of("openai.itemId", StateValue.string("fc-1"))))),
                        new Message(
                                Role.TOOL,
                                List.of(new FunctionResultContent(
                                        "call-1", StateValue.object(Map.of("temperature", StateValue.integer(21))))))),
                ChatOptions.empty());
        OpenAITransport.Request boundary = OpenAIRequestMapper.map(
                request, OpenAIChatClientOptions.builder().model("gpt-test").build(), OpenAIResponseOptions.defaults());

        // Act
        List<ResponseInputItem> sdk =
                OpenAISdkRequestMapper.map(boundary).input().orElseThrow().asResponse();

        // Assert
        assertThat(sdk).hasSize(3);
        assertThat(sdk.get(1).asFunctionCall().callId()).isEqualTo("call-1");
        assertThat(sdk.get(1).asFunctionCall().id()).contains("fc-1");
        assertThat(sdk.get(1).asFunctionCall().arguments()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(sdk.get(2).asFunctionCallOutput().callId()).isEqualTo("call-1");
        assertThat(sdk.get(2).asFunctionCallOutput().output().asString()).contains("\"temperature\":21");
    }

    @Test
    void sdkResponseMapper_shouldMapOfficialSdkResponseWithoutLeakingSdkModels() throws Exception {
        // Arrange
        String json = """
                {
                  "id": "resp_sdk",
                  "created_at": 1.25,
                  "metadata": {"tenant": "contoso"},
                  "model": "gpt-test",
                  "output": [
                    {
                      "id": "msg_sdk",
                      "type": "message",
                      "role": "assistant",
                      "status": "completed",
                      "content": [
                        {"type": "output_text", "text": "hello", "annotations": []}
                      ]
                    },
                    {
                      "id": "fc_sdk",
                      "type": "function_call",
                      "call_id": "call_sdk",
                      "name": "lookup",
                      "arguments": "{\\"city\\":\\"Paris\\"}",
                      "status": "completed"
                    }
                  ],
                  "service_tier": "default",
                  "status": "completed",
                  "usage": {
                    "input_tokens": 4,
                    "input_tokens_details": {"cached_tokens": 1},
                    "output_tokens": 2,
                    "output_tokens_details": {"reasoning_tokens": 1},
                    "total_tokens": 6
                  }
                }
                """;
        Response sdkResponse = ObjectMappers.jsonMapper().readValue(json, Response.class);

        // Act
        OpenAITransport.Response mapped = OpenAISdkResponseMapper.map(sdkResponse, "req_sdk");

        // Assert
        assertThat(mapped.responseId()).isEqualTo("resp_sdk");
        assertThat(mapped.createdAt()).hasToString("1970-01-01T00:00:01.250Z");
        assertThat(mapped.outputs())
                .extracting(item -> item.getClass().getSimpleName())
                .containsExactly("TextOutput", "FunctionCallOutput");
        assertThat(mapped.usage()).isEqualTo(new OpenAITransport.Usage(4, 2, 6, 1L, 1L));
        assertThat(mapped.metadata())
                .containsEntry("tenant", StateValue.string("contoso"))
                .containsEntry("openai.serviceTier", StateValue.string("default"));
        assertThat(mapped.requestId()).isEqualTo("req_sdk");
    }

    @Test
    void sdkStreamMapper_shouldCorrelateFunctionCallWhenAddedItemOmitsOptionalId() {
        // Arrange
        ResponseFunctionToolCall call = ResponseFunctionToolCall.builder()
                .arguments("")
                .callId("call-1")
                .name("lookup")
                .build();
        ResponseOutputItemAddedEvent added = ResponseOutputItemAddedEvent.builder()
                .item(call)
                .outputIndex(0)
                .sequenceNumber(1)
                .build();
        ResponseFunctionCallArgumentsDoneEvent done = ResponseFunctionCallArgumentsDoneEvent.builder()
                .arguments("{\"city\":\"Paris\"}")
                .itemId("fc-1")
                .name("lookup")
                .outputIndex(0)
                .sequenceNumber(2)
                .build();
        OpenAISdkResponseMapper.StreamEventMapper mapper = new OpenAISdkResponseMapper.StreamEventMapper();

        // Act
        List<OpenAITransport.StreamEvent> start = mapper.map(ResponseStreamEvent.ofOutputItemAdded(added));
        List<OpenAITransport.StreamEvent> terminalArguments =
                mapper.map(ResponseStreamEvent.ofFunctionCallArgumentsDone(done));

        // Assert
        assertThat(start).isEmpty();
        assertThat(terminalArguments)
                .extracting(item -> item.getClass().getSimpleName())
                .containsExactly("FunctionCallStarted", "FunctionArgumentsDone");
        OpenAITransport.FunctionArgumentsDone mapped =
                (OpenAITransport.FunctionArgumentsDone) terminalArguments.getLast();
        assertThat(mapped.callId()).isEqualTo("call-1");
        assertThat(mapped.itemId()).isEqualTo("fc-1");
    }

    @ParameterizedTest
    @EnumSource(OpenAIImageOutputFormat.class)
    void sdkResponseMapper_shouldMapEverySupportedGeneratedImageFormat(OpenAIImageOutputFormat format)
            throws Exception {
        // Arrange
        Response sdkResponse = imageResponse();

        // Act
        OpenAITransport.Response mapped = OpenAISdkResponseMapper.map(sdkResponse, "req_image", format);

        // Assert
        OpenAITransport.ImageOutput image =
                (OpenAITransport.ImageOutput) mapped.outputs().getFirst();
        assertThat(image.mediaType()).isEqualTo(format.mediaType());
        assertThat(image.uri()).isEqualTo(URI.create("data:" + format.mediaType() + ";base64,AQID"));
        DataContent content = (DataContent) OpenAIResponseMapper.map(mapped)
                .messages()
                .getFirst()
                .contents()
                .getFirst();
        assertThat(content.mediaType()).isEqualTo(format.mediaType());
        assertThat(content.data()).containsExactly(1, 2, 3);
    }

    @Test
    void sdkResponseMapper_shouldUseDocumentedPngDefaultAndRejectUnknownRequestedFormat() throws Exception {
        // Arrange
        Response sdkResponse = imageResponse();

        // Act
        OpenAITransport.Response mapped = OpenAISdkResponseMapper.map(sdkResponse, "req_image", null);

        // Assert
        OpenAITransport.ImageOutput image =
                (OpenAITransport.ImageOutput) mapped.outputs().getFirst();
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.uri().toString()).startsWith("data:image/png;base64,");
        assertThatThrownBy(() -> OpenAIImageOutputFormat.valueOf("GIF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sdkRequestMapper_shouldMapOnlyKnownSdkEnumMembers() {
        // Arrange / Act / Assert
        for (OpenAIServiceTier tier : OpenAIServiceTier.values()) {
            assertThat(responseOptions(tier, null).serviceTier().orElseThrow().isValid())
                    .isTrue();
        }
        for (OpenAITruncation truncation : OpenAITruncation.values()) {
            assertThat(responseOptions(null, truncation)
                            .truncation()
                            .orElseThrow()
                            .isValid())
                    .isTrue();
        }
        for (OpenAITransport.ImageDetail detail : OpenAITransport.ImageDetail.values()) {
            OpenAITransport.Request request = imageInputRequest(detail);
            assertThat(OpenAISdkRequestMapper.map(request)
                            .input()
                            .orElseThrow()
                            .asResponse()
                            .getFirst()
                            .asEasyInputMessage()
                            .content()
                            .asResponseInputMessageContentList()
                            .getFirst()
                            .asInputImage()
                            .detail()
                            .isValid())
                    .isTrue();
        }
        for (OpenAIReasoningEffort effort : OpenAIReasoningEffort.values()) {
            ResponseCreateParams mapped = responseOptions(
                    OpenAIResponseOptions.builder().reasoningEffort(effort).build());
            assertThat(mapped.reasoning().orElseThrow().effort().orElseThrow().isValid())
                    .isTrue();
        }
        for (OpenAIReasoningSummary summary : OpenAIReasoningSummary.values()) {
            ResponseCreateParams mapped = responseOptions(
                    OpenAIResponseOptions.builder().reasoningSummary(summary).build());
            assertThat(mapped.reasoning().orElseThrow().summary().orElseThrow().isValid())
                    .isTrue();
        }
        for (OpenAIImageOutputFormat format : OpenAIImageOutputFormat.values()) {
            ResponseCreateParams mapped = responseOptions(
                    OpenAIResponseOptions.builder().imageOutputFormat(format).build());
            assertThat(mapped.tools()
                            .orElseThrow()
                            .getFirst()
                            .asImageGeneration()
                            .outputFormat()
                            .orElseThrow()
                            .isValid())
                    .isTrue();
        }
    }

    private static ResponseCreateParams responseOptions(OpenAIServiceTier tier, OpenAITruncation truncation) {
        OpenAIResponseOptions.Builder options = OpenAIResponseOptions.builder();
        if (tier != null) {
            options.serviceTier(tier);
        }
        if (truncation != null) {
            options.truncation(truncation);
        }
        return responseOptions(options.build());
    }

    private static ResponseCreateParams responseOptions(OpenAIResponseOptions options) {
        OpenAITransport.Request request = new OpenAITransport.Request(
                "gpt-test",
                List.of(new OpenAITransport.MessageInput(
                        OpenAITransport.InputRole.USER, List.of(new OpenAITransport.TextInput("hello")))),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                options);
        return OpenAISdkRequestMapper.map(request);
    }

    private static OpenAITransport.Request imageInputRequest(OpenAITransport.ImageDetail detail) {
        return new OpenAITransport.Request(
                "gpt-test",
                List.of(new OpenAITransport.MessageInput(
                        OpenAITransport.InputRole.USER,
                        List.of(new OpenAITransport.ImageInput(
                                URI.create("https://example.test/image.png"), "image/png", detail)))),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                OpenAIResponseOptions.defaults());
    }

    private static Response imageResponse() throws Exception {
        String json = """
                {
                  "id": "resp_image",
                  "created_at": 1,
                  "metadata": {},
                  "model": "gpt-image-1",
                  "output": [
                    {
                      "id": "ig_1",
                      "type": "image_generation_call",
                      "status": "completed",
                      "result": "AQID"
                    }
                  ],
                  "status": "completed"
                }
                """;
        return ObjectMappers.jsonMapper().readValue(json, Response.class);
    }
}
