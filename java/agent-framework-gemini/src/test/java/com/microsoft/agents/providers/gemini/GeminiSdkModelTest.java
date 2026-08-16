// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.ErrorContent;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GeminiSdkModelTest {
    private static final GeminiChatClientOptions OPTIONS = GeminiChatClientOptions.builder()
            .model("gemini-test")
            .apiKey("test-key")
            .build();

    @Test
    void mapper_shouldBuildOfficialSdkModelsAndMapResponseMetadata() {
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.SYSTEM, "system"), Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .maxTokens(25)
                        .structuredOutput(StructuredOutputOptions.jsonSchema(
                                "answer", Map.of("type", StateValue.string("object"))))
                        .build(),
                List.of(tool()),
                ToolMode.AUTO,
                null);
        GeminiMapper.MappedRequest mapped = GeminiMapper.request(request, OPTIONS);
        GenerateContentResponse sdkResponse = GenerateContentResponse.builder()
                .responseId("response-1")
                .modelVersion("gemini-test")
                .candidates(Candidate.builder()
                        .index(0)
                        .content(Content.builder()
                                .role("model")
                                .parts(
                                        Part.fromText("answer"),
                                        Part.builder()
                                                .functionCall(FunctionCall.builder()
                                                        .id("call-1")
                                                        .name("lookup")
                                                        .args(Map.of("city", "Paris"))
                                                        .build())
                                                .build())
                                .build())
                        .finishReason(new FinishReason(FinishReason.Known.STOP))
                        .build())
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(3)
                        .candidatesTokenCount(2)
                        .totalTokenCount(5)
                        .thoughtsTokenCount(1)
                        .build())
                .build();

        var response = GeminiMapper.response(sdkResponse);

        assertThat(mapped.model()).isEqualTo("gemini-test");
        assertThat(mapped.config().systemInstruction()).isPresent();
        assertThat(mapped.config().responseJsonSchema()).isPresent();
        assertThat(response.responseId()).isEqualTo("response-1");
        assertThat(response.text()).isEqualTo("answer");
        assertThat(response.usage().totalTokens()).contains(java.math.BigInteger.valueOf(5));
        assertThat(response.messages().getFirst().contents())
                .anySatisfy(value -> assertThat(value)
                        .isEqualTo(new FunctionCallContent(
                                "call-1", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris"))))));
    }

    @Test
    void streamAssembler_shouldMapUsageOnlyTerminalAndToolCalls() {
        GeminiMapper.StreamAssembler assembler = new GeminiMapper.StreamAssembler();
        assembler.accept(GenerateContentResponse.builder()
                .responseId("response-2")
                .modelVersion("gemini-test")
                .candidates(Candidate.builder()
                        .index(0)
                        .content(Content.builder()
                                .role("model")
                                .parts(Part.builder()
                                        .functionCall(FunctionCall.builder()
                                                .id("call-2")
                                                .name("lookup")
                                                .args(Map.of("city", "Paris"))
                                                .build())
                                        .build())
                                .build())
                        .finishReason(new FinishReason(FinishReason.Known.STOP))
                        .build())
                .build());
        assembler.accept(GenerateContentResponse.builder()
                .responseId("response-2")
                .modelVersion("gemini-test")
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(4)
                        .candidatesTokenCount(3)
                        .totalTokenCount(7)
                        .build())
                .build());

        var terminal = assembler.finish();

        assertThat(terminal.usage().totalTokens()).contains(java.math.BigInteger.valueOf(7));
        assertThat(terminal.contents().getFirst())
                .isEqualTo(new FunctionCallContent(
                        "call-2", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris")))));
    }

    @Test
    void malformedAndUnexpectedToolFinishes_shouldMapToErrorAndNeverInvokeTools() {
        GenerateContentResponse malformed = toolFailureResponse(FinishReason.Known.MALFORMED_FUNCTION_CALL);

        ChatResponse finite = GeminiMapper.response(malformed);
        GeminiMapper.StreamAssembler assembler = new GeminiMapper.StreamAssembler();
        assembler.accept(GenerateContentResponse.builder()
                .candidates(Candidate.builder()
                        .index(0)
                        .content(Content.builder()
                                .role("model")
                                .parts(Part.builder()
                                        .functionCall(FunctionCall.builder()
                                                .id("unsafe-call")
                                                .name("lookup")
                                                .args(Map.of("city", "Paris"))
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());
        assembler.accept(toolFailureResponse(FinishReason.Known.UNEXPECTED_TOOL_CALL));
        ChatResponseUpdate terminal = assembler.finish();

        assertThat(finite.finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.of("error"));
        assertThat(finite.messages().getFirst().contents())
                .noneMatch(FunctionCallContent.class::isInstance)
                .anyMatch(ErrorContent.class::isInstance);
        assertThat(finite.metadata())
                .containsEntry(
                        "gemini.finishReason", StateValue.string(FinishReason.Known.MALFORMED_FUNCTION_CALL.name()))
                .containsEntry("gemini.finishMessage", StateValue.string("provider rejected call"));
        assertThat(terminal.finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.of("error"));
        assertThat(terminal.contents())
                .noneMatch(FunctionCallContent.class::isInstance)
                .anyMatch(ErrorContent.class::isInstance);
        assertThat(terminal.metadata())
                .containsEntry(
                        "gemini.finishReason", StateValue.string(FinishReason.Known.UNEXPECTED_TOOL_CALL.name()));

        AtomicInteger invocations = new AtomicInteger();
        FunctionTool function = FunctionTool.create(tool(), (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(arguments);
        });
        GeminiTransport transport = new FixedTransport(finite);
        try (GeminiChatClient client =
                GeminiChatClient.builder().options(OPTIONS).transport(transport).build()) {
            var response = new ChatAgent(client, List.of(function))
                    .runAsync("invoke the tool")
                    .toCompletableFuture()
                    .join();
            assertThat(response.messages()).flatExtracting(Message::contents).anyMatch(ErrorContent.class::isInstance);
        }
        assertThat(invocations).hasValue(0);
    }

    @Test
    void optionsAndFixture_shouldEnforceSecurityAndRegistration() {
        assertThatThrownBy(() -> GeminiChatClientOptions.builder()
                        .model("test")
                        .apiKey("key")
                        .endpoint("http://remote.example")
                        .allowedHosts(Set.of("remote.example"))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThat(OPTIONS.toString()).doesNotContain("test-key");
        assertThat(new ConformanceFixtureLoader()
                        .loadDefault()
                        .requireCase("JCF-PROVIDERS-005")
                        .caseId())
                .isEqualTo("JCF-PROVIDERS-005");
    }

    private static ToolMetadata tool() {
        return new ToolMetadata(
                "lookup",
                "Looks up a city.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of("type", StateValue.string("object"))));
    }

    private static GenerateContentResponse toolFailureResponse(FinishReason.Known reason) {
        return GenerateContentResponse.builder()
                .responseId("failed-response")
                .modelVersion("gemini-test")
                .candidates(Candidate.builder()
                        .index(0)
                        .content(Content.builder()
                                .role("model")
                                .parts(Part.builder()
                                        .functionCall(FunctionCall.builder()
                                                .id("unsafe-call")
                                                .name("lookup")
                                                .args(Map.of("city", "Paris"))
                                                .build())
                                        .build())
                                .build())
                        .finishReason(new FinishReason(reason))
                        .finishMessage("provider rejected call")
                        .build())
                .build();
    }

    private record FixedTransport(ChatResponse response) implements GeminiTransport {
        @Override
        public CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest request, GeminiChatClientOptions options, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, GeminiChatClientOptions options, RunCancellation cancellation) {
            throw new UnsupportedOperationException("finite test transport");
        }
    }
}
