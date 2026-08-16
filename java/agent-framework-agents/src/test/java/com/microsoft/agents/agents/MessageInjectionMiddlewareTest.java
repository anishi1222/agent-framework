// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MessageInjectionMiddlewareTest {
    @Test
    void run_shouldAppendPrequeuedMessagesToFirstModelTurn_andDrainQueue() {
        // Arrange
        AgentSession session = new AgentSession();
        MessageInjectionMiddleware.enqueueMessages(session, "queued");
        FakeChatClient client = new FakeChatClient().enqueue(response("done", "conversation-1"));

        // Act
        AgentRunResult<Void> result;
        try (ChatAgent agent = agent(client, List.of())) {
            result = agent.run(session, "original");
        }

        // Assert
        assertThat(result.response().orElseThrow().messages().getLast().text()).isEqualTo("done");
        assertThat(client.requests())
                .singleElement()
                .satisfies(request ->
                        assertThat(request.messages()).extracting(Message::text).containsExactly("original", "queued"));
        assertThat(MessageInjectionMiddleware.getPendingMessages(session)).isEmpty();
        assertThat(session.state().get(MessageInjectionMiddleware.PENDING_MESSAGES_STATE_KEY))
                .containsInstanceOf(StateValue.ArrayValue.class);
    }

    @Test
    void run_shouldContinueAfterNonActionableResponse_andPropagateConversationId() {
        // Arrange
        AgentSession session = new AgentSession();
        FakeChatClient client = new FakeChatClient()
                .enqueueFinite((request, cancellation) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, "queued during call");
                    return CompletableFuture.completedFuture(response("first", "conversation-7"));
                })
                .enqueue(response("second", "conversation-7"));

        // Act
        AgentRunResult<Void> result;
        try (ChatAgent agent = agent(client, List.of())) {
            result = agent.run(session, "original");
        }

        // Assert
        assertThat(result.response().orElseThrow().messages().getLast().text()).isEqualTo("second");
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).messages())
                .extracting(Message::text)
                .containsExactly("original", "first", "queued during call");
        assertThat(client.requests().get(1).options().conversationId()).isEqualTo("conversation-7");
    }

    @Test
    void run_shouldTreatInformationalFunctionCallsAsNonActionable() {
        // Arrange
        AgentSession session = new AgentSession();
        FunctionCallContent informational =
                new FunctionCallContent("hosted-1", "hosted_search", StateValue.object(Map.of()), true, Map.of());
        FakeChatClient client = new FakeChatClient()
                .enqueueFinite((request, cancellation) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, "queued after hosted call");
                    return CompletableFuture.completedFuture(ChatResponse.builder()
                            .messages(List.of(new Message(Role.ASSISTANT, List.of(informational))))
                            .conversationId("conversation-1")
                            .finishReason(FinishReason.STOP)
                            .build());
                })
                .enqueue(response("done", "conversation-1"));

        // Act
        try (ChatAgent agent = agent(client, List.of())) {
            agent.run(session, "original");
        }

        // Assert
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).messages().getLast().text()).isEqualTo("queued after hosted call");
    }

    @Test
    void run_shouldInjectToolQueuedMessageAfterFunctionResult() {
        // Arrange
        AgentSession session = new AgentSession();
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = tool("inject", () -> {
            invocations.incrementAndGet();
            MessageInjectionMiddleware.enqueueMessages(session, "queued from tool");
        });
        FunctionCallContent call = new FunctionCallContent("call-1", "inject", StateValue.object(Map.of()));
        FakeChatClient client = new FakeChatClient()
                .enqueue(ChatResponse.builder()
                        .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build())
                .enqueue(response("done", null));

        // Act
        try (ChatAgent agent = agent(client, List.of(tool))) {
            agent.run(session, "original");
        }

        // Assert
        assertThat(invocations).hasValue(1);
        assertThat(client.requests()).hasSize(2);
        List<Message> secondTurn = client.requests().get(1).messages();
        assertThat(secondTurn.get(secondTurn.size() - 2).contents())
                .singleElement()
                .isInstanceOf(FunctionResultContent.class);
        assertThat(secondTurn.getLast().text()).isEqualTo("queued from tool");
    }

    @Test
    void runStreaming_shouldContinueWhenMessageIsQueuedDuringStream() {
        // Arrange
        AgentSession session = new AgentSession();
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming((request, cancellation) -> {
                    MessageInjectionMiddleware.enqueueMessages(session, "queued while streaming");
                    return publisher(update(0, "first", FinishReason.STOP));
                })
                .enqueueStreaming(List.of(update(1, "second", FinishReason.STOP)));
        RecordingSubscriber subscriber = new RecordingSubscriber();

        // Act
        try (ChatAgent agent = agent(client, List.of())) {
            agent.runStreaming(session, List.of(Message.text(Role.USER, "original")), RunOptions.empty())
                    .subscribe(subscriber);
            subscriber.terminal().join();
        }

        // Assert
        assertThat(subscriber.errors).isEmpty();
        assertThat(subscriber.updates).extracting(AgentResponseUpdate::text).containsExactly("first", "second");
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).messages().getLast().text()).isEqualTo("queued while streaming");
    }

    @Test
    void run_shouldFailClearlyWhenMiddlewareIsEnabledWithoutSession() {
        // Arrange
        FakeChatClient client = new FakeChatClient();

        // Act and assert
        try (ChatAgent agent = agent(client, List.of())) {
            assertThatThrownBy(() ->
                            agent.runAsync("original").toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("requires an AgentSession");
        }
        assertThat(client.requests()).isEmpty();
    }

    private static ChatAgent agent(FakeChatClient client, List<FunctionTool> tools) {
        return new ChatAgent(
                client,
                AgentMetadata.create(),
                ChatOptions.empty(),
                tools,
                List.of(),
                List.of(),
                List.of(new MessageInjectionMiddleware()),
                List.of(),
                null);
    }

    private static FunctionTool tool(String name, Runnable action) {
        ToolMetadata metadata = new ToolMetadata(
                name,
                "Test tool " + name,
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.NEVER_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            action.run();
            return CompletableFuture.completedFuture(StateValue.string("tool result"));
        });
    }

    private static ChatResponse response(String text, String conversationId) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .conversationId(conversationId)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static ChatResponseUpdate update(long sequence, String text, FinishReason finishReason) {
        return ChatResponseUpdate.builder()
                .sequence(sequence)
                .role(Role.ASSISTANT)
                .contents(List.of(new TextContent(text)))
                .finishReason(finishReason)
                .build();
    }

    private static Flow.Publisher<ChatResponseUpdate> publisher(ChatResponseUpdate update) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean completed = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (count <= 0) {
                    subscriber.onError(new IllegalArgumentException("demand"));
                } else if (completed.compareAndSet(false, true)) {
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                completed.set(true);
            }
        });
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
        private final List<AgentResponseUpdate> updates = new ArrayList<>();

        private final List<Throwable> errors = new ArrayList<>();

        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AgentResponseUpdate item) {
            updates.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            errors.add(throwable);
            terminal.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            terminal.complete(null);
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }
    }
}
