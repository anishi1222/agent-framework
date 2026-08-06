// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.ToolChoice;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChatAgentTest {
    @Test
    void runAsync_shouldDiscardFiniteUpdatesBeyondStreamingBufferAndPreserveResponseHistory() {
        // Arrange
        List<Message> providerMessages = IntStream.range(0, 300)
                .mapToObj(index -> Message.text(Role.ASSISTANT, "message-" + index))
                .toList();
        FakeChatClient client = new FakeChatClient()
                .enqueue(ChatResponse.builder()
                        .messages(providerMessages)
                        .finishReason(FinishReason.STOP)
                        .build());

        // Act
        AgentResponse<Void> result;
        try (ChatAgent agent = new ChatAgent(client)) {
            result = agent.runAsync("many messages").toCompletableFuture().join();
        }

        // Assert
        assertThat(result.messages()).hasSize(300);
        assertThat(result.messages().getFirst().text()).isEqualTo("message-0");
        assertThat(result.messages().getLast().text()).isEqualTo("message-299");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void runAsync_shouldPreserveFiniteResponseAndExplicitContext_withoutTools() {
        // Arrange
        UsageDetails usage =
                UsageDetails.builder().inputTokens(3).outputTokens(2).build();
        ChatResponse providerResponse = response(
                List.of(Message.text(Role.ASSISTANT, "hello")),
                "response-1",
                FinishReason.STOP,
                usage,
                Map.of("provider", StateValue.string("fake")),
                List.of(4L));
        FakeChatClient client = new FakeChatClient().enqueue(providerResponse);
        AgentMetadata metadata = new AgentMetadata("agent-1", "sample", "test agent");
        ChatOptions options = ChatOptions.builder().model("fake-model").build();

        // Act
        AgentResponse<Void> result;
        try (ChatAgent agent = new ChatAgent(client, metadata, options, List.of())) {
            result = agent.runAsync("hello").toCompletableFuture().join();
        }

        // Assert
        assertThat(result.messages()).containsExactly(Message.text(Role.ASSISTANT, "hello"));
        assertThat(result.responseId()).isEqualTo("response-1");
        assertThat(result.agentId()).isEqualTo("agent-1");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage()).isEqualTo(usage);
        assertThat(result.metadata()).containsEntry("provider", StateValue.string("fake"));
        assertThat(result.updateSequences()).containsExactly(4L);
        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.messages()).containsExactly(Message.text(Role.USER, "hello"));
            assertThat(request.tools()).isEmpty();
            assertThat(request.toolMode()).isEqualTo(ToolMode.NONE);
            assertThat(request.options().toolChoice()).isEqualTo(ToolChoice.NONE);
            assertThat(request.runContext()).isNotNull();
            assertThat(request.runContext().runId()).isNotBlank();
            assertThat(request.runContext().agent()).isEqualTo(metadata);
        });
    }

    @Test
    void runAsync_shouldMapToolLoopHistoryAndFoldUsageInTurnOrder() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = functionTool("lookup", invocations, StateValue.string("tool-value"));
        FunctionCallContent call = new FunctionCallContent("call-1", "lookup", StateValue.object(Map.of()));
        FakeChatClient client = new FakeChatClient()
                .enqueue(response(
                        List.of(new Message(Role.ASSISTANT, List.of(call))),
                        "response-tools",
                        FinishReason.TOOL_CALLS,
                        UsageDetails.builder().inputTokens(2).build(),
                        Map.of(),
                        List.of(0L)))
                .enqueue(response(
                        List.of(Message.text(Role.ASSISTANT, "final answer")),
                        "response-final",
                        FinishReason.STOP,
                        UsageDetails.builder().inputTokens(3).outputTokens(4).build(),
                        Map.of("turn", StateValue.string("final")),
                        List.of(1L)));

        // Act
        AgentResponse<Void> result;
        try (ChatAgent agent = new ChatAgent(client, List.of(tool))) {
            result = agent.run("use the tool");
        }

        // Assert
        assertThat(invocations).hasValue(1);
        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages().get(0).contents()).containsExactly(call);
        assertThat(result.messages().get(1).role()).isEqualTo(Role.TOOL);
        assertThat(result.messages().get(1).contents())
                .singleElement()
                .isInstanceOfSatisfying(FunctionResultContent.class, functionResult -> {
                    assertThat(functionResult.callId()).isEqualTo("call-1");
                    assertThat(functionResult.result()).isEqualTo(StateValue.string("tool-value"));
                });
        assertThat(result.messages().get(2).text()).isEqualTo("final answer");
        assertThat(result.responseId()).isEqualTo("response-final");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage().inputTokens()).contains(java.math.BigInteger.valueOf(5));
        assertThat(result.usage().outputTokens()).contains(java.math.BigInteger.valueOf(4));
        assertThat(result.metadata()).containsEntry("turn", StateValue.string("final"));
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(0).runContext().runId())
                .isEqualTo(client.requests().get(1).runContext().runId());
        assertThat(client.requests().get(1).messages())
                .extracting(message -> message.role().value())
                .containsExactly("user", "assistant", "tool");
    }

    @Test
    void runStreaming_shouldExposeProviderAndToolUpdatesInOrder_withSingleTerminal() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = functionTool("lookup", invocations, StateValue.string("value"));
        FunctionCallContent call = new FunctionCallContent("call-stream", "lookup", StateValue.object(Map.of()));
        FakeChatClient client = new FakeChatClient()
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .sequence(0)
                        .role(Role.ASSISTANT)
                        .contents(List.of(call))
                        .finishReason(FinishReason.TOOL_CALLS)
                        .build()))
                .enqueueStreaming(List.of(ChatResponseUpdate.builder()
                        .sequence(1)
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent("done")))
                        .finishReason(FinishReason.STOP)
                        .build()));

        // Act
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);
        try (ChatAgent agent = new ChatAgent(client, List.of(tool))) {
            agent.runStreaming("stream").subscribe(subscriber);
            subscriber.terminal().join();
        }

        // Assert
        assertThat(invocations).hasValue(1);
        assertThat(subscriber.errors()).isEmpty();
        assertThat(subscriber.completions()).hasValue(1);
        assertThat(subscriber.updates()).hasSize(3);
        assertThat(subscriber.updates().stream()
                        .flatMap(update -> update.contents().stream())
                        .map(content -> content.kind()))
                .containsExactly("functionCall", "functionResult", "text");
        assertThat(subscriber.updates())
                .extracting(AgentResponseUpdate::agentId)
                .doesNotContainNull();
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(0).runContext().runId())
                .isEqualTo(client.requests().get(1).runContext().runId());
    }

    @Test
    void runAsync_shouldPropagateProviderFailure_withoutSuccessFallback() {
        // Arrange
        AgentExecutionException providerFailure = new AgentExecutionException("provider unavailable");
        FakeChatClient client = new FakeChatClient().enqueueFailure(providerFailure);

        // Act and assert
        try (ChatAgent agent = new ChatAgent(client)) {
            assertThatThrownBy(
                            () -> agent.runAsync("hello").toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(providerFailure);
        }
    }

    @Test
    void runAsync_shouldExposeTypedProcessLocalContinuationWhenToolApprovalIsRequired() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool =
                functionTool("write", ToolApprovalMode.ALWAYS_REQUIRE, invocations, StateValue.string("unused"));
        FunctionCallContent call = new FunctionCallContent("call-approval", "write", StateValue.object(Map.of()));
        FakeChatClient client = new FakeChatClient()
                .enqueue(response(
                        List.of(new Message(Role.ASSISTANT, List.of(call))),
                        "approval",
                        FinishReason.TOOL_CALLS,
                        null,
                        Map.of(),
                        List.of()));

        // Act and assert
        try (ChatAgent agent = new ChatAgent(client, List.of(tool))) {
            assertThatThrownBy(
                            () -> agent.runAsync("write").toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(ApprovalRequiredException.class)
                    .hasMessageContaining("tool approval continuation");
        }
        assertThat(invocations).hasValue(0);
    }

    @Test
    void runStreaming_shouldPropagateProviderFailureExactlyOnce() {
        // Arrange
        AgentExecutionException providerFailure = new AgentExecutionException("stream unavailable");
        FakeChatClient client = new FakeChatClient().enqueueStreamingFailure(providerFailure);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            agent.runStreaming("hello").subscribe(subscriber);
            assertThatThrownBy(() -> subscriber.terminal().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(providerFailure);
        }

        // Assert
        assertThat(subscriber.errors()).containsExactly(providerFailure);
        assertThat(subscriber.completions()).hasValue(0);
    }

    @Test
    void constructor_shouldRejectRequiredToolChoice_withoutTools() {
        // Arrange
        ChatOptions options =
                ChatOptions.builder().toolChoice(ToolChoice.REQUIRED).build();

        // Act and assert
        assertThatThrownBy(() -> new ChatAgent(new FakeChatClient(), AgentMetadata.create(), options, List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requires at least one tool");
    }

    @Test
    void publicInputOverloads_shouldRejectBlankNullAndNullElements_asValidationFailures() {
        // Arrange
        FakeChatClient client = new FakeChatClient();

        // Act and assert
        try (ChatAgent agent = new ChatAgent(client)) {
            assertThatThrownBy(() -> agent.runAsync("  ")).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> agent.runAsync((List<Message>) null)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> agent.runAsync(java.util.Arrays.asList((Message) null)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    private static FunctionTool functionTool(String name, AtomicInteger invocations, StateValue result) {
        return functionTool(name, ToolApprovalMode.NEVER_REQUIRE, invocations, result);
    }

    private static FunctionTool functionTool(
            String name, ToolApprovalMode approvalMode, AtomicInteger invocations, StateValue result) {
        ToolMetadata metadata = new ToolMetadata(
                name,
                "Test tool " + name,
                Set.of(ToolCapability.FUNCTION),
                approvalMode,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(result);
        });
    }

    private static ChatResponse response(
            List<Message> messages,
            String responseId,
            FinishReason finishReason,
            UsageDetails usage,
            Map<String, StateValue> metadata,
            List<Long> sequences) {
        return new ChatResponse(
                messages,
                responseId,
                "conversation-1",
                "fake-model",
                Instant.parse("2026-08-05T00:00:00Z"),
                finishReason,
                usage,
                null,
                metadata,
                sequences);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<AgentResponseUpdate> {
        private final long initialDemand;

        private final List<AgentResponseUpdate> updates = new ArrayList<>();

        private final List<Throwable> errors = new ArrayList<>();

        private final AtomicInteger completions = new AtomicInteger();

        private final CompletableFuture<Void> terminal = new CompletableFuture<>();

        private Flow.Subscription subscription;

        private RecordingSubscriber(long initialDemand) {
            this.initialDemand = initialDemand;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (initialDemand != 0) {
                subscription.request(initialDemand);
            }
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
            completions.incrementAndGet();
            terminal.complete(null);
        }

        private List<AgentResponseUpdate> updates() {
            return List.copyOf(updates);
        }

        private List<Throwable> errors() {
            return List.copyOf(errors);
        }

        private AtomicInteger completions() {
            return completions;
        }

        private CompletableFuture<Void> terminal() {
            return terminal;
        }

        @SuppressWarnings("unused")
        private Flow.Subscription subscription() {
            return subscription;
        }
    }
}
