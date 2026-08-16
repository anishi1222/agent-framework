// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExtensionHelpersTest {
    @Test
    void messageSources_shouldDefaultCopyReuseAndRejectMalformedAttribution() {
        // Arrange
        Message original = Message.text(Role.USER, "hello");

        // Act
        Message attributed =
                MessageSources.withSource(original, AgentRequestMessageSourceType.AI_CONTEXT_PROVIDER, "provider-1");
        Message unchanged =
                MessageSources.withSource(attributed, AgentRequestMessageSourceType.AI_CONTEXT_PROVIDER, "provider-1");
        Message malformed = new Message(
                Role.USER,
                List.of(),
                null,
                null,
                Map.of(
                        AgentRequestMessageSourceAttribution.METADATA_KEY,
                        StateValue.object(Map.of("sourceType", StateValue.integer(1)))));

        // Assert
        assertThat(MessageSources.sourceType(original)).isEqualTo(AgentRequestMessageSourceType.EXTERNAL);
        assertThat(MessageSources.sourceId(original)).isNull();
        assertThat(attributed).isNotSameAs(original);
        assertThat(original.metadata()).isEmpty();
        assertThat(MessageSources.sourceType(attributed)).isEqualTo(AgentRequestMessageSourceType.AI_CONTEXT_PROVIDER);
        assertThat(MessageSources.sourceId(attributed)).isEqualTo("provider-1");
        assertThat(unchanged).isSameAs(attributed);
        assertThat(MessageSources.attribution(malformed)).isEmpty();
    }

    @Test
    void agentSessions_shouldReplaceDetachedInMemoryHistory() {
        // Arrange
        AgentSession session = new AgentSession("session-1");
        List<Message> replacement = new java.util.ArrayList<>(List.of(Message.text(Role.USER, "first")));

        // Act
        AgentSessions.setInMemoryHistory(session, replacement);
        replacement.set(0, Message.text(Role.USER, "mutated"));
        List<Message> history = AgentSessions.inMemoryHistory(session);

        // Assert
        assertThat(history).extracting(Message::text).containsExactly("first");
    }

    @Test
    void chatAgent_shouldAttributeHistoryAndContextMessagesBeforeProviderCall() {
        // Arrange
        AgentSession session = new AgentSession("session-attribution");
        AgentSessions.setInMemoryHistory(session, List.of(Message.text(Role.ASSISTANT, "history")));
        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        ChatClient client = new ChatClient() {
            @Override
            public CompletionStage<ChatResponse> completeAsync(
                    ChatClientRequest request, RunCancellation cancellation) {
                captured.set(request);
                return CompletableFuture.completedStage(ChatResponse.builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                        .build());
            }

            @Override
            public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                    ChatClientRequest request, RunCancellation cancellation) {
                return subscriber -> subscriber.onError(new AssertionError("Streaming is not expected."));
            }
        };
        ContextProvider context = new ContextProvider() {
            @Override
            public String id() {
                return "context-1";
            }

            @Override
            public CompletionStage<ContextContribution> provideAsync(ContextProviderRequest request) {
                return CompletableFuture.completedStage(new ContextContribution(
                        List.of(), List.of(Message.text(Role.SYSTEM, "context")), Map.of(), List.of()));
            }
        };
        ChatAgent agent = new ChatAgent(
                client,
                new AgentMetadata("agent-1", null, null),
                ChatOptions.empty(),
                List.of(),
                List.of(context),
                List.of(),
                List.of(),
                List.of(),
                null);

        // Act
        try (agent) {
            agent.runAsync(session, List.of(Message.text(Role.USER, "external")), RunOptions.empty())
                    .toCompletableFuture()
                    .join();
        }

        // Assert
        List<Message> messages = captured.get().messages();
        assertThat(messages).extracting(Message::text).containsExactly("history", "context", "external");
        assertThat(MessageSources.sourceType(messages.get(0))).isEqualTo(AgentRequestMessageSourceType.CHAT_HISTORY);
        assertThat(MessageSources.sourceId(messages.get(0))).isEqualTo("history");
        assertThat(MessageSources.sourceType(messages.get(1)))
                .isEqualTo(AgentRequestMessageSourceType.AI_CONTEXT_PROVIDER);
        assertThat(MessageSources.sourceId(messages.get(1))).isEqualTo("context-1");
        assertThat(MessageSources.sourceType(messages.get(2))).isEqualTo(AgentRequestMessageSourceType.EXTERNAL);
    }
}
