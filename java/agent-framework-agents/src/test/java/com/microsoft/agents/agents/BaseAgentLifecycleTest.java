// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.SynchronousExecutionException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BaseAgentLifecycleTest {
    @Test
    void startRunCancellation_shouldCancelProviderAndTerminateExactlyOnce() throws Exception {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> pending);

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            RunHandle<AgentResponse<Void>> handle = agent.startRun("wait");
            assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();
            boolean first = handle.cancel();
            boolean second = handle.cancel();

            // Assert
            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            assertThat(client.cancellations())
                    .singleElement()
                    .satisfies(cancellation ->
                            assertThat(cancellation.isCancellationRequested()).isTrue());
        }
    }

    @Test
    void callerOwnedCancellation_shouldPropagateThroughFiniteExecution() throws Exception {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> pending);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();

        // Act
        CompletionStage<AgentResponse<Void>> result;
        try (ChatAgent agent = new ChatAgent(client)) {
            result = agent.runAsync("wait", RunOptions.empty(), cancellation);
            assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();
            cancellation.cancel();

            // Assert
            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(RunCancelledException.class);
            assertThat(client.cancellations())
                    .singleElement()
                    .satisfies(providerCancellation -> assertThat(providerCancellation.isCancellationRequested())
                            .isTrue());
        }
    }

    @Test
    void synchronousRunInterruption_shouldCancelRestoreInterruptAndWrapInterruption() throws Exception {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> pending);
        ChatAgent agent = new ChatAgent(client);
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        // Act
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                agent.run("wait");
            } catch (Throwable failure) {
                observed.set(failure);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        // Assert
        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get())
                .isInstanceOf(SynchronousExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(interrupted).isTrue();
        assertThat(client.cancellations())
                .singleElement()
                .satisfies(cancellation ->
                        assertThat(cancellation.isCancellationRequested()).isTrue());
        agent.close();
    }

    @Test
    void synchronousRunFailure_shouldRetainTypedAsynchronousCause() {
        // Arrange
        AgentExecutionException providerFailure = new AgentExecutionException("typed provider failure");
        FakeChatClient client = new FakeChatClient().enqueueFailure(providerFailure);

        // Act and assert
        try (ChatAgent agent = new ChatAgent(client)) {
            assertThatThrownBy(() -> agent.run("fail"))
                    .isInstanceOf(SynchronousExecutionException.class)
                    .hasCause(providerFailure);
        }
    }

    @Test
    void close_shouldCancelAwaitActiveRunAndRejectNewRuns() throws Exception {
        // Arrange
        CompletableFuture<ChatResponse> pending = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> pending);
        ChatAgent agent = new ChatAgent(client);
        RunHandle<AgentResponse<Void>> handle = agent.startRun("wait");
        assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();

        // Act
        agent.close();

        // Assert
        assertThat(agent.isClosed()).isTrue();
        assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RunCancelledException.class);
        assertThat(client.cancellations())
                .singleElement()
                .satisfies(cancellation ->
                        assertThat(cancellation.isCancellationRequested()).isTrue());
        assertThatThrownBy(() -> agent.startRun("after close"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("closed");
        agent.close();
    }

    @Test
    void close_shouldNotCloseCallerOwnedExecutor() {
        // Arrange
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FakeChatClient client = new FakeChatClient().enqueue(simpleResponse("ok"));
        ChatAgent agent = new ChatAgent(
                client, new AgentMetadata("agent-executor", null, null), ChatOptions.empty(), List.of(), executor);

        // Act
        AgentResponse<Void> result = agent.run("hello");
        agent.close();

        // Assert
        assertThat(result.text()).isEqualTo("ok");
        assertThat(executor.isShutdown()).isFalse();
        executor.shutdownNow();
    }

    @Test
    void concurrentRuns_shouldKeepExplicitContextsIsolatedAcrossContinuations() {
        // Arrange
        FakeChatClient client = new FakeChatClient();
        client.fallbackFinite(
                (request, cancellation) -> CompletableFuture.completedFuture(simpleResponse(request.runContext()
                                .runId() + ":" + request.messages().getFirst().text())));
        List<String> inputs = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "input-" + index)
                .toList();

        // Act
        List<AgentResponse<Void>> results;
        try (ChatAgent agent = new ChatAgent(
                client, new AgentMetadata("agent-concurrent", "concurrent", null), ChatOptions.empty(), List.of())) {
            List<CompletionStage<AgentResponse<Void>>> stages =
                    inputs.stream().map(agent::runAsync).toList();
            CompletableFuture.allOf(stages.stream()
                            .map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new))
                    .join();
            results = stages.stream()
                    .map(CompletionStage::toCompletableFuture)
                    .map(CompletableFuture::join)
                    .toList();
        }

        // Assert
        assertThat(client.requests()).hasSize(inputs.size());
        Set<String> runIds = new HashSet<>();
        client.requests().forEach(request -> {
            AgentRunContext context = request.runContext();
            assertThat(context).isNotNull();
            assertThat(context.runId()).isNotBlank();
            assertThat(runIds.add(context.runId())).isTrue();
            assertThat(context.inputMessages()).isEqualTo(request.messages());
            assertThat(context.agent().id()).isEqualTo("agent-concurrent");
            assertThat(context.metadata()).isEqualTo(Map.of());
        });
        assertThat(results).allSatisfy(response -> {
            assertThat(response.agentId()).isEqualTo("agent-concurrent");
            assertThat(response.text()).contains(":input-");
        });
    }

    private static ChatResponse simpleResponse(String text) {
        return new ChatResponse(
                List.of(Message.text(Role.ASSISTANT, text)),
                null,
                null,
                null,
                null,
                FinishReason.STOP,
                null,
                null,
                Map.of(),
                List.of());
    }
}
