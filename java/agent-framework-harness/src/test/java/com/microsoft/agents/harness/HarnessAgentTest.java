// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.InMemoryHistoryProvider;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.harness.files.InMemoryAgentFileStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HarnessAgentTest {
    @Test
    void harnessAgent_shouldAssembleDefaultProvidersInStableOrder() {
        HarnessAgentOptions options = HarnessAgentOptions.builder()
                .fileMemoryStore(new InMemoryAgentFileStore())
                .build();

        try (HarnessAgent agent = new HarnessAgent(new EchoChatClient(), options)) {
            assertThat(agent.contextProviders())
                    .extracting(ContextProvider::getClass)
                    .containsExactly(
                            InMemoryHistoryProvider.class,
                            TodoProvider.class,
                            AgentModeProvider.class,
                            FileMemoryProvider.class);
            assertThat(agent.chatAgent().chatOptions().instructions()).startsWith(HarnessAgent.DEFAULT_INSTRUCTIONS);
            assertThat(agent.run("hello").text()).isEqualTo("echo:hello");
        }
    }

    @Test
    void harnessAgent_shouldHonorProviderFlagsAndOptInFeatures() {
        Agent<Void> background = new NamedNoopAgent("researcher");
        HarnessAgentOptions options = HarnessAgentOptions.builder()
                .harnessInstructions("")
                .agentInstructions("Agent-specific instructions.")
                .disableTodo(true)
                .disableMode(true)
                .disableFileMemory(true)
                .fileAccessStore(new InMemoryAgentFileStore())
                .backgroundAgents(List.of(background))
                .loopEvaluators(
                        List.of((context, cancellation) -> CompletableFuture.completedFuture(LoopEvaluation.stop())))
                .build();

        try (HarnessAgent agent = new HarnessAgent(new EchoChatClient(), options)) {
            assertThat(agent.contextProviders())
                    .extracting(ContextProvider::getClass)
                    .containsExactly(
                            InMemoryHistoryProvider.class, FileAccessProvider.class, BackgroundAgentsProvider.class);
            assertThat(agent.chatAgent().chatOptions().instructions()).isEqualTo("Agent-specific instructions.");
            assertThat(agent.loopAgent()).isPresent();
        }
    }

    @Test
    void harnessAgent_shouldCreateTodoAndBackgroundLoopEvaluatorsFromConvenienceOptions() {
        HarnessAgentOptions options = HarnessAgentOptions.builder()
                .fileMemoryStore(new InMemoryAgentFileStore())
                .backgroundAgents(List.of(new NamedNoopAgent("worker")))
                .loopOnTodos(java.util.Set.of("execute"))
                .loopOnBackgroundTasks(true)
                .build();

        try (HarnessAgent agent = new HarnessAgent(new EchoChatClient(), options)) {
            assertThat(agent.loopAgent()).isPresent();
            assertThat(agent.loopAgent().orElseThrow().evaluators())
                    .extracting(Object::getClass)
                    .containsExactly(TodoCompletionLoopEvaluator.class, BackgroundTaskCompletionLoopEvaluator.class);
        }
    }

    private static final class EchoChatClient implements ChatClient {
        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            String input = request.messages().getLast().text();
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "echo:" + input)))
                    .responseId("echo-response")
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class NamedNoopAgent implements Agent<Void> {
        private final AgentMetadata metadata;

        private NamedNoopAgent(String name) {
            metadata = new AgentMetadata("agent-" + name, name, null);
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public com.microsoft.agents.core.RunHandle<com.microsoft.agents.core.AgentResponse<Void>> startRun(
                List<Message> messages, com.microsoft.agents.core.RunOptions options, RunCancellation cancellation) {
            var source = new com.microsoft.agents.core.RunHandleSource<com.microsoft.agents.core.AgentResponse<Void>>(
                    cancellation);
            source.tryComplete(com.microsoft.agents.core.AgentResponse.<Void>builder()
                    .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                    .build());
            return source.handle();
        }

        @Override
        public Flow.Publisher<com.microsoft.agents.core.AgentResponseUpdate> runStreaming(
                List<Message> messages, com.microsoft.agents.core.RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {}
            });
        }
    }
}
