// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StructuredOutputOptions;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseMetrics;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConverseTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

class BedrockSdkModelTest {
    private static final BedrockChatClientOptions OPTIONS =
            BedrockChatClientOptions.builder().model("model").build();

    @Test
    void mapper_shouldBuildRealConverseRequestAndMapRealResponse() {
        ChatClientRequest frameworkRequest = new ChatClientRequest(
                List.of(Message.text(Role.SYSTEM, "system"), Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .maxTokens(25)
                        .temperature(0.2)
                        .structuredOutput(StructuredOutputOptions.jsonSchema(
                                "answer", Map.of("type", StateValue.string("object"))))
                        .build(),
                List.of(tool()),
                ToolMode.AUTO,
                null);

        var request = BedrockMapper.request(frameworkRequest, OPTIONS, codec());
        ConverseResponse sdkResponse = ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(
                        software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(
                                        ContentBlock.fromText("answer"),
                                        ContentBlock.fromToolUse(ToolUseBlock.builder()
                                                .toolUseId("call-1")
                                                .name("lookup")
                                                .input(Document.fromMap(Map.of("city", Document.fromString("Paris"))))
                                                .build()))
                                .build()))
                .stopReason(StopReason.TOOL_USE)
                .usage(TokenUsage.builder()
                        .inputTokens(3)
                        .outputTokens(2)
                        .totalTokens(5)
                        .build())
                .metrics(ConverseMetrics.builder().latencyMs(10L).build())
                .build();

        var response = BedrockMapper.response(sdkResponse);

        assertThat(request.modelId()).isEqualTo("model");
        assertThat(request.system())
                .singleElement()
                .satisfies(value -> assertThat(value.text()).isEqualTo("system"));
        assertThat(request.toolConfig().tools()).hasSize(1);
        assertThat(request.outputConfig()).isNotNull();
        assertThat(response.text()).isEqualTo("answer");
        assertThat(response.finishReason()).isEqualTo(com.microsoft.agents.core.FinishReason.TOOL_CALLS);
        assertThat(response.messages().getFirst().contents())
                .anySatisfy(value -> assertThat(value)
                        .isEqualTo(new FunctionCallContent(
                                "call-1", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris"))))));
    }

    @Test
    void streamAssembler_shouldHandleFragmentedToolJsonAndUsageTerminal() {
        BedrockMapper.StreamAssembler assembler = new BedrockMapper.StreamAssembler("request-1", codec());
        assembler.onStart(ContentBlockStartEvent.builder()
                .contentBlockIndex(0)
                .start(ContentBlockStart.builder()
                        .toolUse(ToolUseBlockStart.builder()
                                .toolUseId("call-2")
                                .name("lookup")
                                .build())
                        .build())
                .build());
        assembler.onDelta(ContentBlockDeltaEvent.builder()
                .contentBlockIndex(0)
                .delta(ContentBlockDelta.builder()
                        .toolUse(ToolUseBlockDelta.builder().input("{\"city\":").build())
                        .build())
                .build());
        assembler.onDelta(ContentBlockDeltaEvent.builder()
                .contentBlockIndex(0)
                .delta(ContentBlockDelta.builder()
                        .toolUse(ToolUseBlockDelta.builder().input("\"Paris\"}").build())
                        .build())
                .build());
        var toolUpdate = assembler.onStop(
                ContentBlockStopEvent.builder().contentBlockIndex(0).build());
        assembler.onMessageStop(
                MessageStopEvent.builder().stopReason(StopReason.TOOL_USE).build());
        assembler.onMetadata(ConverseStreamMetadataEvent.builder()
                .usage(TokenUsage.builder()
                        .inputTokens(4)
                        .outputTokens(3)
                        .totalTokens(7)
                        .build())
                .build());

        var terminal = assembler.terminal();

        assertThat(toolUpdate)
                .singleElement()
                .satisfies(update -> assertThat(update.contents().getFirst())
                        .isEqualTo(new FunctionCallContent(
                                "call-2", "lookup", StateValue.object(Map.of("city", StateValue.string("Paris"))))));
        assertThat(terminal.usage().totalTokens()).contains(java.math.BigInteger.valueOf(7));
        assertThat(terminal.metadata()).containsEntry("bedrock.requestId", StateValue.string("request-1"));
    }

    @Test
    void response_shouldFailActionablyForGuardAndUnknownBlocksInsteadOfDroppingThem() {
        ContentBlock guard = ContentBlock.fromGuardContent(GuardrailConverseContentBlock.fromText(
                GuardrailConverseTextBlock.builder().text("guarded").build()));

        assertThatThrownBy(() -> BedrockMapper.response(response(guard)))
                .isInstanceOf(BedrockProviderException.class)
                .extracting("kind")
                .isEqualTo("unsupported_guard_content");
        assertThatThrownBy(() ->
                        BedrockMapper.response(response(ContentBlock.builder().build())))
                .isInstanceOf(BedrockProviderException.class)
                .extracting("kind")
                .isEqualTo("unsupported_response_block");
    }

    @Test
    void conformanceFixture_shouldBeRegistered() {
        assertThat(new ConformanceFixtureLoader()
                        .loadDefault()
                        .requireCase("JCF-PROVIDERS-004")
                        .caseId())
                .isEqualTo("JCF-PROVIDERS-004");
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

    private static StrictJsonCodec codec() {
        return new StrictJsonCodec(1024 * 1024, 1024 * 1024, 64, 1024 * 1024, 1000, 1000);
    }

    private static ConverseResponse response(ContentBlock block) {
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(
                        software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                                .role(ConversationRole.ASSISTANT)
                                .content(block)
                                .build()))
                .stopReason(StopReason.END_TURN)
                .build();
    }
}
