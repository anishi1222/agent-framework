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
import com.microsoft.agents.core.ReasoningContent;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAIRequestMapperTest {
    private static final OpenAIChatClientOptions DEFAULTS =
            OpenAIChatClientOptions.builder().model("default-model").build();

    @Test
    void map_shouldPreserveRolesRichContentOptionsAndTools() {
        // Arrange
        ChatOptions options = ChatOptions.builder()
                .model("request-model")
                .temperature(0.25)
                .topP(0.75)
                .maxTokens(123)
                .allowMultipleToolCalls(true)
                .user("user-42")
                .store(false)
                .instructions("Be concise.")
                .metadata(Map.of("tenant", StateValue.string("contoso")))
                .build();
        List<Message> messages = List.of(
                Message.text(Role.SYSTEM, "system"),
                new Message(
                        Role.USER,
                        List.of(
                                new TextContent("hello"),
                                new DataContent(new byte[] {1, 2, 3}, "image/png"),
                                new UriContent(
                                        URI.create("https://example.test/file.pdf"),
                                        "application/pdf",
                                        Map.of("filename", StateValue.string("file.pdf"))))),
                new Message(
                        Role.ASSISTANT,
                        List.of(
                                new FunctionCallContent(
                                        "call-1",
                                        "lookup",
                                        StateValue.object(Map.of("city", StateValue.string("Paris"))),
                                        false,
                                        Map.of("openai.itemId", StateValue.string("fc_1"))),
                                new ReasoningContent("rs_1", "summary", "encrypted", Map.of()))),
                new Message(
                        Role.TOOL,
                        List.of(new FunctionResultContent(
                                "call-1", StateValue.object(Map.of("temperature", StateValue.integer(21)))))));
        ChatClientRequest request =
                new ChatClientRequest(messages, options, List.of(functionTool()), ToolMode.REQUIRED, null);

        // Act
        OpenAITransport.Request mapped = OpenAIRequestMapper.map(request, DEFAULTS, OpenAIResponseOptions.defaults());

        // Assert
        assertThat(mapped.model()).isEqualTo("request-model");
        assertThat(mapped.temperature()).isEqualTo(0.25);
        assertThat(mapped.topP()).isEqualTo(0.75);
        assertThat(mapped.maxOutputTokens()).isEqualTo(123L);
        assertThat(mapped.parallelToolCalls()).isTrue();
        assertThat(mapped.user()).isEqualTo("user-42");
        assertThat(mapped.store()).isFalse();
        assertThat(mapped.metadata()).containsEntry("tenant", "contoso");
        assertThat(mapped.toolChoice()).isEqualTo(OpenAITransport.ToolSelection.REQUIRED);
        assertThat(mapped.tools())
                .extracting(OpenAITransport.FunctionTool::name)
                .containsExactly("lookup");
        assertThat(mapped.input())
                .extracting(item -> item.getClass().getSimpleName())
                .containsExactly(
                        "MessageInput", "MessageInput", "FunctionCallInput", "ReasoningInput", "FunctionResultInput");
        OpenAITransport.FunctionCallInput functionCall =
                (OpenAITransport.FunctionCallInput) mapped.input().get(2);
        assertThat(functionCall.providerItemId()).isEqualTo("fc_1");
        OpenAITransport.MessageInput user =
                (OpenAITransport.MessageInput) mapped.input().get(1);
        assertThat(user.contents())
                .extracting(item -> item.getClass().getSimpleName())
                .containsExactly("TextInput", "ImageInput", "FileInput");
    }

    @Test
    void map_shouldDistinguishResponseAndConversationContinuationIds() {
        // Arrange
        ChatClientRequest responseRequest = new ChatClientRequest(
                List.of(Message.text(Role.USER, "continue")),
                ChatOptions.builder().conversationId("resp_123").build());
        ChatClientRequest conversationRequest = new ChatClientRequest(
                List.of(Message.text(Role.USER, "continue")),
                ChatOptions.builder().conversationId("conv_123").build());

        // Act
        OpenAITransport.Request response =
                OpenAIRequestMapper.map(responseRequest, DEFAULTS, OpenAIResponseOptions.defaults());
        OpenAITransport.Request conversation =
                OpenAIRequestMapper.map(conversationRequest, DEFAULTS, OpenAIResponseOptions.defaults());

        // Assert
        assertThat(response.previousResponseId()).isEqualTo("resp_123");
        assertThat(response.conversationId()).isNull();
        assertThat(conversation.previousResponseId()).isNull();
        assertThat(conversation.conversationId()).isEqualTo("conv_123");
    }

    @Test
    void map_shouldRejectOptionsUnsupportedByResponsesApi() {
        // Arrange
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder().stop(List.of("STOP")).seed(7).build());

        // Act / Assert
        assertThatThrownBy(() -> OpenAIRequestMapper.map(request, DEFAULTS, OpenAIResponseOptions.defaults()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stop", "seed");
    }

    @Test
    void map_shouldRejectUnsupportedContentWithExplicitTypeAndRole() {
        // Arrange
        ChatClientRequest request = new ChatClientRequest(
                List.of(new Message(
                        Role.USER, List.of(new UriContent(URI.create("https://example.test/audio.wav"), "audio/wav")))),
                ChatOptions.empty());

        // Act / Assert
        assertThatThrownBy(() -> OpenAIRequestMapper.map(request, DEFAULTS, OpenAIResponseOptions.defaults()))
                .isInstanceOf(OpenAIUnsupportedContentException.class)
                .hasMessageContaining("audio/wav");
    }

    @Test
    void map_shouldRejectUnknownImageDetailBeforeSdkRequest() {
        // Arrange
        ChatClientRequest request = new ChatClientRequest(
                List.of(new Message(
                        Role.USER,
                        List.of(new UriContent(
                                URI.create("https://example.test/image.png"),
                                "image/png",
                                Map.of("detail", StateValue.string("ultra")))))),
                ChatOptions.empty());

        // Act / Assert
        assertThatThrownBy(() -> OpenAIRequestMapper.map(request, DEFAULTS, OpenAIResponseOptions.defaults()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported OpenAI image detail 'ultra'");
    }

    @Test
    void map_shouldRejectRequiredToolModeWithoutDeclarationsAndNonStringMetadata() {
        // Arrange
        ChatClientRequest missingTools = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")), ChatOptions.empty(), List.of(), ToolMode.REQUIRED, null);
        ChatClientRequest invalidMetadata = new ChatClientRequest(
                List.of(Message.text(Role.USER, "hello")),
                ChatOptions.builder()
                        .metadata(Map.of("attempt", StateValue.integer(1)))
                        .build());

        // Act / Assert
        assertThatThrownBy(() -> OpenAIRequestMapper.map(missingTools, DEFAULTS, OpenAIResponseOptions.defaults()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requires at least one tool");
        assertThatThrownBy(() -> OpenAIRequestMapper.map(invalidMetadata, DEFAULTS, OpenAIResponseOptions.defaults()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be a string");
    }

    static ToolMetadata functionTool() {
        return new ToolMetadata(
                "lookup",
                "Looks up a city.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of(
                        "type",
                        StateValue.string("object"),
                        "properties",
                        StateValue.object(
                                Map.of("city", StateValue.object(Map.of("type", StateValue.string("string"))))),
                        "required",
                        StateValue.array(List.of(StateValue.string("city"))))),
                StateValue.object(Map.of("type", StateValue.string("object"))));
    }
}
