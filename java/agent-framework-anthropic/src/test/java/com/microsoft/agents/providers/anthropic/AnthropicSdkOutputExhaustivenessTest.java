// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.WebSearchResultBlock;
import com.anthropic.models.messages.WebSearchToolResultBlock;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AnthropicSdkOutputExhaustivenessTest {
    @Test
    void currentSdkOutputUnion_shouldRemainExhaustivelyClassified() {
        Set<String> sdkVariants = Arrays.stream(ContentBlock.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("is"))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> method.getReturnType() == boolean.class)
                .map(Method::getName)
                .filter(name -> !"isValid".equals(name))
                .map(name -> Character.toLowerCase(name.charAt(2)) + name.substring(3))
                .collect(Collectors.toSet());

        assertThat(sdkVariants).isEqualTo(AnthropicMapper.SDK_OUTPUT_BLOCK_VARIANTS);
    }

    @Test
    void finiteAndStreamingServerToolBlocks_shouldFailActionablyInsteadOfBeingDropped() {
        ServerToolUseBlock serverTool = ServerToolUseBlock.builder()
                .id("server-1")
                .caller(DirectCaller.builder().build())
                .name(ServerToolUseBlock.Name.WEB_SEARCH)
                .input(JsonValue.from(Map.of("query", "weather")))
                .build();
        WebSearchToolResultBlock webResult = WebSearchToolResultBlock.builder()
                .caller(DirectCaller.builder().build())
                .toolUseId("server-1")
                .contentOfResultBlocks(List.of(WebSearchResultBlock.builder()
                        .url("https://example.com")
                        .title("Example")
                        .encryptedContent("opaque")
                        .pageAge(Optional.empty())
                        .build()))
                .build();

        assertUnsupported(message(ContentBlock.ofServerToolUse(serverTool)), "server_tool_use");
        assertUnsupported(message(ContentBlock.ofWebSearchToolResult(webResult)), "web_search_tool_result");

        RawContentBlockStartEvent start = RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(RawContentBlockStartEvent.ContentBlock.ofServerToolUse(serverTool))
                .build();
        AnthropicMapper.StreamAssembler assembler = new AnthropicMapper.StreamAssembler(codec(), "request-1");
        assertThatThrownBy(() -> assembler.accept(RawMessageStreamEvent.ofContentBlockStart(start)))
                .isInstanceOf(AnthropicProviderException.class)
                .extracting("kind", "providerCode")
                .containsExactly("unsupported_output_block", "server_tool_use");
    }

    @Test
    void requestValidation_shouldAdmitOnlyFrameworkFunctionTools() {
        ToolMetadata webSearch = new ToolMetadata(
                "search",
                "Searches the web.",
                Set.of(ToolCapability.WEB_SEARCH),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of("type", StateValue.string("object"))));
        ChatClientRequest request = new ChatClientRequest(
                List.of(Message.text(Role.USER, "search")),
                ChatOptions.empty(),
                List.of(webSearch),
                ToolMode.AUTO,
                null);

        assertThatThrownBy(() -> AnthropicMapper.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only FUNCTION tools");
    }

    private static void assertUnsupported(com.anthropic.models.messages.Message message, String code) {
        assertThatThrownBy(() -> AnthropicMapper.response(message, "request-1"))
                .isInstanceOf(AnthropicProviderException.class)
                .extracting("kind", "providerCode")
                .containsExactly("unsupported_output_block", code);
    }

    private static com.anthropic.models.messages.Message message(ContentBlock block) {
        return com.anthropic.models.messages.Message.builder()
                .id("message-1")
                .model("claude-test")
                .content(List.of(block))
                .stopReason(StopReason.END_TURN)
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .container(Optional.empty())
                .usage(Usage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(Optional.empty())
                        .cacheReadInputTokens(Optional.empty())
                        .inferenceGeo(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build())
                .build();
    }

    private static StrictJsonCodec codec() {
        return new StrictJsonCodec(1024 * 1024, 1024 * 1024, 64, 1024 * 1024, 1000, 1000);
    }
}
