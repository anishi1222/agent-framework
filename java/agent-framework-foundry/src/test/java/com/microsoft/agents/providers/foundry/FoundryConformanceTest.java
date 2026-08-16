// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.providers.openai.OpenAITransport;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FoundryConformanceTest {
    @Test
    void jcfProviders002_shouldBindProductionRequestClientAndToolLoopPaths() {
        // Arrange
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-PROVIDERS-002");
        ContractTransport transport = new ContractTransport();
        FoundryChatClient client = FoundryChatClient.builder()
                .options(FoundryChatClientOptions.builder()
                        .projectEndpoint("https://resource.services.ai.azure.com/api/projects/project-one")
                        .agentName("weather-agent")
                        .agentVersion("2")
                        .tokenCredential(context -> Mono.just(
                                new AccessToken("token", OffsetDateTime.now().plusHours(1))))
                        .build())
                .transport(transport)
                .build();
        FunctionTool tool = FunctionTool.create(
                metadata(), (context, arguments) -> CompletableFuture.completedFuture(StateValue.string("sunny")));
        ChatAgent agent = new ChatAgent(
                client,
                AgentMetadata.create("weather-agent", null),
                ChatOptions.builder().conversationId("conversation-001").build(),
                List.of(tool));

        // Act
        agent.runAsync("hello").toCompletableFuture().join();
        client.completeAsync(new ChatClientRequest(
                        List.of(Message.text(Role.USER, "isolated")),
                        ChatOptions.builder().conversationId("conversation-002").build()))
                .toCompletableFuture()
                .join();

        // Assert
        List<String> firstRoles = transport.requests.getFirst().input().stream()
                .filter(OpenAITransport.MessageInput.class::isInstance)
                .map(OpenAITransport.MessageInput.class::cast)
                .map(message -> message.role().name().toLowerCase(java.util.Locale.ROOT))
                .toList();
        boolean continuationPreserved = transport.requests.subList(0, 2).stream()
                .allMatch(request -> "conversation-001".equals(request.conversationId()));
        boolean frameworkSessionIsolation =
                "conversation-002".equals(transport.requests.getLast().conversationId());
        assertThat(firstRoles).isEqualTo(strings(fixture.expected(), "requestRoleOrder"));
        assertThat(continuationPreserved)
                .isEqualTo(bool(fixture.expected(), "conversationIdPreservedBetweenToolIterations"));
        assertThat(frameworkSessionIsolation).isEqualTo(bool(fixture.expected(), "frameworkSessionIsolation"));
        assertThat(sharedApiLeaksProviderTypes()).isEqualTo(bool(fixture.expected(), "providerTypesInSharedApi"));
        assertThat(false).isEqualTo(bool(fixture.expected(), "crossLanguageSessionWireCompatible"));
    }

    private static boolean sharedApiLeaksProviderTypes() {
        for (Class<?> type : List.of(
                ChatClient.class,
                ChatClientRequest.class,
                com.microsoft.agents.core.ChatOptions.class,
                com.microsoft.agents.core.ChatResponse.class,
                com.microsoft.agents.core.ChatResponseUpdate.class)) {
            for (var method : type.getMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && (method.toGenericString().contains("com.azure.")
                                || method.toGenericString().contains("providers.foundry"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ToolMetadata metadata() {
        return new ToolMetadata(
                "lookup",
                "Looks up weather.",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
    }

    private static boolean bool(ConformanceValue.ObjectValue expected, String name) {
        return ((ConformanceValue.BooleanValue) expected.require(name)).value();
    }

    private static List<String> strings(ConformanceValue.ObjectValue expected, String name) {
        return ((ConformanceValue.ArrayValue) expected.require(name))
                .values().stream()
                        .map(ConformanceValue.StringValue.class::cast)
                        .map(ConformanceValue.StringValue::value)
                        .toList();
    }

    private static final class ContractTransport implements FoundryTransport {
        private final List<OpenAITransport.Request> requests = new ArrayList<>();

        private final Map<String, Integer> turns = new LinkedHashMap<>();

        @Override
        public CompletionStage<OpenAITransport.Response> completeAsync(
                OpenAITransport.Request request, RunCancellation cancellation) {
            requests.add(request);
            int turn = turns.merge(request.conversationId(), 1, Integer::sum) - 1;
            List<OpenAITransport.OutputItem> outputs = turn == 0
                    ? List.of(new OpenAITransport.FunctionCallOutput(
                            "call-1", "lookup", StateValue.object(Map.of()), "item-1", "completed"))
                    : List.of(new OpenAITransport.TextOutput("message-1", "sunny", false, Map.of()));
            return CompletableFuture.completedFuture(new OpenAITransport.Response(
                    "response-" + (turn + 1),
                    request.conversationId(),
                    "deployment",
                    Instant.EPOCH,
                    OpenAITransport.ResponseStatus.COMPLETED,
                    outputs,
                    null,
                    Map.of(),
                    "request-" + (turn + 1),
                    null,
                    null));
        }

        @Override
        public Flow.Publisher<OpenAITransport.StreamEvent> completeStreaming(
                OpenAITransport.Request request, RunCancellation cancellation) {
            throw new UnsupportedOperationException("This fixture uses finite turns.");
        }
    }
}
