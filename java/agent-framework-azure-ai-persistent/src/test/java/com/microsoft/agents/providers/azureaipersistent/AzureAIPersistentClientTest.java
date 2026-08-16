// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AzureAIPersistentClientTest {
    @Test
    void polling_shouldCompleteWithoutBusyWaiting() {
        FakeTransport transport = new FakeTransport();
        transport.states.add(run(PersistentRunStatus.IN_PROGRESS));
        transport.states.add(run(PersistentRunStatus.COMPLETED));
        try (AzureAIPersistentClient client = client(transport)) {
            PersistentRun terminal = client.startRun(request())
                    .resultAsync()
                    .toCompletableFuture()
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();

            assertThat(terminal.status()).isEqualTo(PersistentRunStatus.COMPLETED);
            assertThat(transport.getRunCalls).hasValue(2);
        }
    }

    @Test
    void polling_shouldRejectUnknownFutureStatusWithoutSuccessFallback() {
        FakeTransport transport = new FakeTransport();
        transport.states.add(run(PersistentRunStatus.fromValue("future_state")));
        try (AzureAIPersistentClient client = client(transport)) {
            assertThatThrownBy(() -> client.startRun(request())
                            .resultAsync()
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(AzureAIPersistentException.class)
                    .rootCause()
                    .extracting(failure -> ((AzureAIPersistentException) failure).serviceCode())
                    .isEqualTo("unknown_run_status");
        }
    }

    @Test
    void cancellation_shouldCancelPollAndRequestServiceCancellation() {
        FakeTransport transport = new FakeTransport();
        transport.pendingGet = new CompletableFuture<>();
        try (AzureAIPersistentClient client = client(transport)) {
            RunHandle<PersistentRun> handle = client.startRun(request());
            handle.cancel();

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(transport.cancelCalls).hasValue(1);
        }
    }

    @Test
    void cancellationBeforeCreateResponse_shouldCancelTheCreatedServiceRun() {
        FakeTransport transport = new FakeTransport();
        transport.pendingCreate = new CompletableFuture<>();
        try (AzureAIPersistentClient client = client(transport)) {
            RunHandle<PersistentRun> handle = client.startRun(request());

            handle.cancel();
            transport.pendingCreate.complete(run(PersistentRunStatus.QUEUED));

            assertThatThrownBy(() -> handle.resultAsync().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(transport.cancelCalls).hasValue(1);
        }
    }

    @Test
    void closeDuringCreateHandoff_shouldSettleHandleAndCancelCreatedRun() {
        FakeTransport transport = new FakeTransport();
        transport.pendingCreate = new CompletableFuture<>();
        AzureAIPersistentClient client = client(transport);
        RunHandle<PersistentRun> handle = client.startRun(request());

        client.close();
        transport.pendingCreate.complete(run(PersistentRunStatus.QUEUED));

        assertThatThrownBy(() -> handle.resultAsync()
                        .toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(transport.cancelCalls).hasValue(1);
    }

    @Test
    void awaitRunCancellation_shouldStopOnlyLocalObservationByDefault() {
        FakeTransport transport = new FakeTransport();
        transport.pendingGet = new CompletableFuture<>();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (AzureAIPersistentClient client = client(transport)) {
            CompletionStage<PersistentRun> observation = client.awaitRunAsync("thread-one", "run-one", cancellation);

            cancellation.cancel();

            assertThatThrownBy(() -> observation.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(transport.cancelCalls).hasValue(0);
        }
    }

    @Test
    void awaitRunCancellation_shouldRequestRemoteCancellationOnlyWhenExplicitlyEnabled() {
        FakeTransport transport = new FakeTransport();
        transport.pendingGet = new CompletableFuture<>();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        try (AzureAIPersistentClient client = client(transport)) {
            CompletionStage<PersistentRun> observation =
                    client.awaitRunAsync("thread-one", "run-one", cancellation, true);

            cancellation.cancel();

            assertThatThrownBy(() -> observation.toCompletableFuture().join())
                    .hasRootCauseInstanceOf(com.microsoft.agents.core.RunCancelledException.class);
            assertThat(transport.cancelCalls).hasValue(1);
        }
    }

    @Test
    void awaitRunTimeout_shouldStopOnlyLocalObservationByDefault() {
        FakeTransport transport = new FakeTransport();
        transport.pendingGet = new CompletableFuture<>();
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .initialPollDelay(Duration.ofMillis(1))
                .maxPollDelay(Duration.ofMillis(2))
                .pollJitter(0)
                .timeout(Duration.ofMillis(25))
                .build();
        try (AzureAIPersistentClient client = new AzureAIPersistentClient(options, transport)) {
            CompletionStage<PersistentRun> observation =
                    client.awaitRunAsync("thread-one", "run-one", new DefaultRunCancellation());

            assertThatThrownBy(() -> observation
                            .toCompletableFuture()
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join())
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(transport.cancelCalls).hasValue(0);
        }
    }

    @Test
    void timeout_shouldCompleteEvenWhenPollRequestNeverReturns() {
        FakeTransport transport = new FakeTransport();
        transport.pendingGet = new CompletableFuture<>();
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .initialPollDelay(Duration.ofMillis(1))
                .maxPollDelay(Duration.ofMillis(2))
                .pollJitter(0)
                .timeout(Duration.ofMillis(25))
                .build();
        try (AzureAIPersistentClient client = new AzureAIPersistentClient(options, transport)) {
            RunHandle<PersistentRun> handle = client.startRun(request());

            assertThatThrownBy(() -> handle.resultAsync()
                            .toCompletableFuture()
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join())
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(transport.cancelCalls).hasValue(1);
        }
    }

    @Test
    void close_shouldLeaveCallerSchedulerAndServiceResourcesUntouched() {
        FakeTransport transport = new FakeTransport();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .scheduler(scheduler)
                .build();
        AzureAIPersistentClient client = new AzureAIPersistentClient(options, transport);

        client.close();

        assertThat(scheduler.isShutdown()).isFalse();
        assertThat(transport.deleteAgentCalls).hasValue(0);
        assertThat(transport.deleteThreadCalls).hasValue(0);
        scheduler.shutdownNow();
    }

    @Test
    void rejectedApproval_shouldAwaitAsynchronousServiceCancellation() {
        FakeTransport transport = new FakeTransport();
        transport.cancelStatus = PersistentRunStatus.CANCELLING;
        transport.states.add(run(PersistentRunStatus.CANCELLED));
        try (AzureAIPersistentClient client = client(transport)) {
            PersistentRun terminal = client.continueRunAsync(
                            new PersistentRunContinuation(
                                    "thread-one",
                                    "run-one",
                                    PersistentContinuationKind.APPROVAL,
                                    List.of(),
                                    false,
                                    null),
                            new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(terminal.status()).isEqualTo(PersistentRunStatus.CANCELLED);
            assertThat(transport.cancelCalls).hasValue(1);
            assertThat(transport.getRunCalls).hasValue(1);
        }
    }

    @Test
    void agentSession_shouldPersistIdsAndAvoidDuplicateStableMessages() {
        FakeTransport transport = new FakeTransport();
        transport.states.add(run(PersistentRunStatus.COMPLETED));
        transport.states.add(run(PersistentRunStatus.COMPLETED));
        try (AzureAIPersistentClient client = client(transport);
                AzureAIPersistentAgent agent = client.asAgent(agentDefinition())) {
            AgentSession session = new AgentSession("session-one");
            Message message = Message.builder(Role.USER)
                    .messageId("message-one")
                    .contents(List.of(new com.microsoft.agents.core.TextContent("hello")))
                    .build();

            agent.runAsync(session, List.of(message), RunOptions.empty(), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
            agent.runAsync(session, List.of(message), RunOptions.empty(), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();

            assertThat(transport.createMessageCalls).hasValue(1);
            assertThat(session.state().containsKey(AzureAIPersistentAgent.AGENT_ID_STATE_KEY))
                    .isTrue();
            assertThat(session.state().containsKey(AzureAIPersistentAgent.THREAD_ID_STATE_KEY))
                    .isTrue();
            assertThat(session.state().containsKey(AzureAIPersistentAgent.RUN_ID_STATE_KEY))
                    .isTrue();
        }
    }

    private static AzureAIPersistentClient client(FakeTransport transport) {
        AzureAIPersistentClientOptions options = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .initialPollDelay(Duration.ofMillis(1))
                .maxPollDelay(Duration.ofMillis(2))
                .pollJitter(0)
                .timeout(Duration.ofSeconds(2))
                .build();
        return new AzureAIPersistentClient(options, transport);
    }

    private static PersistentRunRequest request() {
        return new PersistentRunRequest("thread-one", "agent-one", null, null, null, Map.of());
    }

    private static PersistentRun run(PersistentRunStatus status) {
        return new PersistentRun(
                "run-one",
                "thread-one",
                "agent-one",
                status,
                null,
                null,
                null,
                null,
                Instant.now(),
                status.equals(PersistentRunStatus.COMPLETED) ? Instant.now() : null,
                Map.of());
    }

    private static PersistentAgentDefinition agentDefinition() {
        return new PersistentAgentDefinition(
                "agent-one", "deployment", "Agent", "test", null, List.of(), Map.of(), Instant.now());
    }

    private static final class FakeTransport implements PersistentTransport {
        private final ArrayDeque<PersistentRun> states = new ArrayDeque<>();
        private final AtomicInteger getRunCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger createMessageCalls = new AtomicInteger();
        private final AtomicInteger deleteAgentCalls = new AtomicInteger();
        private final AtomicInteger deleteThreadCalls = new AtomicInteger();
        private PersistentRunStatus cancelStatus = PersistentRunStatus.CANCELLED;
        private CompletableFuture<PersistentRun> pendingCreate;
        private CompletableFuture<PersistentRun> pendingGet;

        @Override
        public CompletionStage<PersistentAgentDefinition> createAgentAsync(
                PersistentAgentCreateRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedStage(agentDefinition());
        }

        @Override
        public CompletionStage<PersistentAgentDefinition> getAgentAsync(String agentId, RunCancellation cancellation) {
            return CompletableFuture.completedStage(agentDefinition());
        }

        @Override
        public CompletionStage<PersistentAgentDefinition> updateAgentAsync(
                String agentId, PersistentAgentCreateRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedStage(agentDefinition());
        }

        @Override
        public CompletionStage<Void> deleteAgentAsync(String agentId, RunCancellation cancellation) {
            deleteAgentCalls.incrementAndGet();
            return CompletableFuture.completedStage(null);
        }

        @Override
        public CompletionStage<PersistentPage<PersistentAgentDefinition>> listAgentsAsync(
                int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentPage<>(List.of(agentDefinition()), null, false));
        }

        @Override
        public CompletionStage<PersistentThread> createThreadAsync(
                Map<String, String> metadata, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentThread("thread-one", Instant.now(), metadata));
        }

        @Override
        public CompletionStage<PersistentThread> getThreadAsync(String threadId, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentThread(threadId, Instant.now(), Map.of()));
        }

        @Override
        public CompletionStage<Void> deleteThreadAsync(String threadId, RunCancellation cancellation) {
            deleteThreadCalls.incrementAndGet();
            return CompletableFuture.completedStage(null);
        }

        @Override
        public CompletionStage<PersistentMessage> createMessageAsync(
                String threadId,
                Role role,
                String text,
                List<PersistentAttachment> attachments,
                Map<String, String> metadata,
                RunCancellation cancellation) {
            createMessageCalls.incrementAndGet();
            return CompletableFuture.completedStage(new PersistentMessage(
                    "input-" + createMessageCalls.get(),
                    threadId,
                    null,
                    role,
                    text,
                    attachments,
                    metadata,
                    Instant.now()));
        }

        @Override
        public CompletionStage<PersistentPage<PersistentMessage>> listMessagesAsync(
                String threadId, String runId, int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentPage<>(
                    List.of(new PersistentMessage(
                            "response-one",
                            threadId,
                            runId,
                            Role.ASSISTANT,
                            "done",
                            List.of(),
                            Map.of(),
                            Instant.now())),
                    null,
                    false));
        }

        @Override
        public CompletionStage<PersistentRun> createRunAsync(
                PersistentRunRequest request, RunCancellation cancellation) {
            return pendingCreate == null
                    ? CompletableFuture.completedStage(run(PersistentRunStatus.QUEUED))
                    : pendingCreate;
        }

        @Override
        public CompletionStage<PersistentRun> getRunAsync(String threadId, String runId, RunCancellation cancellation) {
            getRunCalls.incrementAndGet();
            if (pendingGet != null) {
                return pendingGet;
            }
            PersistentRun value = states.poll();
            return CompletableFuture.completedStage(value == null ? run(PersistentRunStatus.COMPLETED) : value);
        }

        @Override
        public CompletionStage<PersistentPage<PersistentRun>> listRunsAsync(
                String threadId, int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(
                    new PersistentPage<>(List.of(run(PersistentRunStatus.COMPLETED)), null, false));
        }

        @Override
        public CompletionStage<PersistentRun> cancelRunAsync(
                String threadId, String runId, RunCancellation cancellation) {
            cancelCalls.incrementAndGet();
            return CompletableFuture.completedStage(run(cancelStatus));
        }

        @Override
        public CompletionStage<PersistentRun> submitToolOutputsAsync(
                String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation) {
            return CompletableFuture.completedStage(run(PersistentRunStatus.IN_PROGRESS));
        }

        @Override
        public Flow.Publisher<PersistentRunEvent> createRunStreaming(
                PersistentRunRequest request, RunCancellation cancellation) {
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
