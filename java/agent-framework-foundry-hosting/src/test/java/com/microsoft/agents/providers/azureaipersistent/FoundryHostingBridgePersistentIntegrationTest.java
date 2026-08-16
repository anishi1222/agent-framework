// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.azure.AzureAccessToken;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRunRequest;
import com.microsoft.agents.hosting.foundry.FoundryHostedSession;
import com.microsoft.agents.hosting.foundry.FoundryHostedSessionKey;
import com.microsoft.agents.hosting.foundry.FoundryHostedSessionStore;
import com.microsoft.agents.hosting.foundry.FoundryHostingBridge;
import com.microsoft.agents.hosting.foundry.FoundryHostingOptions;
import com.microsoft.agents.hosting.foundry.InMemoryFoundryHostedSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FoundryHostingBridgePersistentIntegrationTest {
    @Test
    void concurrentSameMessageId_shouldSubmitExactlyOnceAndRetryCasConflict() throws Exception {
        DelayedTransport transport = new DelayedTransport();
        ConflictOnceStore sessions = new ConflictOnceStore();
        AzureAIPersistentClientOptions clientOptions = AzureAIPersistentClientOptions.builder()
                .endpoint("https://resource.services.ai.azure.com/api/projects/project")
                .authenticationProvider((request, cancellation) -> CompletableFuture.completedStage(
                        new AzureAccessToken("token", Instant.now().plusSeconds(3600))))
                .initialPollDelay(Duration.ofMillis(1))
                .maxPollDelay(Duration.ofMillis(2))
                .pollJitter(0)
                .timeout(Duration.ofSeconds(2))
                .build();
        FoundryHostingOptions hostingOptions =
                new FoundryHostingOptions(10, Duration.ofHours(1), 10, Duration.ofMinutes(5), 10, 3);
        HostingRegistry registry = new HostingRegistry();
        try (sessions;
                AzureAIPersistentClient client = new AzureAIPersistentClient(clientOptions, transport);
                AzureAIPersistentAgent agent = client.asAgent(agentDefinition());
                FoundryHostingBridge bridge = new FoundryHostingBridge(registry, sessions, hostingOptions)) {
            bridge.registerPersistentAgent("persistent", agent);
            try (HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
                HostingRequestContext context = context("principal-one", "tenant-one");
                HostingOutcome warmup = dispatcher
                        .runAsync(
                                context,
                                HostingRouteKind.AGENT,
                                "persistent",
                                request("conversation-one", Message.text(Role.USER, "warmup")))
                        .toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();
                assertThat(warmup.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);

                Message shared = Message.builder(Role.USER)
                        .messageId("message-one")
                        .contents(List.of(new com.microsoft.agents.core.TextContent("hello")))
                        .build();
                CompletionStage<HostingOutcome> first = dispatcher.runAsync(
                        context, HostingRouteKind.AGENT, "persistent", request("conversation-one", shared));
                assertThat(transport.messageStarted.await(5, TimeUnit.SECONDS)).isTrue();

                CompletionStage<HostingOutcome> concurrent = dispatcher.runAsync(
                        context, HostingRouteKind.AGENT, "persistent", request("conversation-one", shared));
                assertThat(concurrent.toCompletableFuture()).isNotDone();
                transport.releaseMessage.complete(null);
                HostingOutcome firstResult = first.toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();
                HostingOutcome concurrentResult = concurrent
                        .toCompletableFuture()
                        .orTimeout(5, TimeUnit.SECONDS)
                        .join();

                assertThat(firstResult.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
                assertThat(concurrentResult.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
                assertThat(transport.sharedMessageCalls).hasValue(1);
                assertThat(sessions.conflicts).hasValue(1);
                FoundryHostedSession stored = sessions.loadAsync(new FoundryHostedSessionKey(
                                "persistent", "principal-one", "tenant-one", "conversation-one"))
                        .toCompletableFuture()
                        .join()
                        .orElseThrow();
                assertThat(stored.submittedMessageIds()).containsExactly("message-one");
            }
        }
    }

    private static HostingRunRequest request(String conversationId, Message message) {
        return new HostingRunRequest(
                List.of(message),
                null,
                RunOptions.empty(),
                Map.of(FoundryHostingBridge.CONVERSATION_ID_METADATA, StateValue.string(conversationId)));
    }

    private static HostingRequestContext context(String principal, String isolation) {
        return new HostingRequestContext(
                "request-" + principal,
                "correlation-" + principal,
                new HostingPrincipal(principal, isolation),
                Map.of(),
                Map.of(),
                new DefaultRunCancellation());
    }

    private static PersistentAgentDefinition agentDefinition() {
        return new PersistentAgentDefinition(
                "agent-one", "deployment", "Agent", "test", null, List.of(), Map.of(), Instant.now());
    }

    private static PersistentRun run(String runId, String threadId, PersistentRunStatus status) {
        return new PersistentRun(
                runId,
                threadId,
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

    private static final class ConflictOnceStore implements FoundryHostedSessionStore, AutoCloseable {
        private final InMemoryFoundryHostedSessionStore delegate =
                new InMemoryFoundryHostedSessionStore(10, Duration.ofHours(1));
        private final AtomicInteger conflicts = new AtomicInteger();

        @Override
        public CompletionStage<Optional<FoundryHostedSession>> loadAsync(FoundryHostedSessionKey key) {
            return delegate.loadAsync(key);
        }

        @Override
        public CompletionStage<FoundryHostedSession> saveAsync(FoundryHostedSession session, long expectedRevision) {
            if (!session.submittedMessageIds().isEmpty() && conflicts.compareAndSet(0, 1)) {
                return CompletableFuture.failedFuture(new StorageConflictException("forced reservation conflict"));
            }
            return delegate.saveAsync(session, expectedRevision);
        }

        @Override
        public CompletionStage<Boolean> deleteAsync(FoundryHostedSessionKey key) {
            return delegate.deleteAsync(key);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class DelayedTransport implements PersistentTransport {
        private final AtomicInteger runSequence = new AtomicInteger();
        private final AtomicInteger messageSequence = new AtomicInteger();
        private final AtomicInteger sharedMessageCalls = new AtomicInteger();
        private final CountDownLatch messageStarted = new CountDownLatch(1);
        private final CompletableFuture<Void> releaseMessage = new CompletableFuture<>();

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
            return CompletableFuture.completedStage(null);
        }

        @Override
        public CompletionStage<PersistentPage<PersistentAgentDefinition>> listAgentsAsync(
                int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentPage<>(List.of(), null, false));
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
            int sequence = messageSequence.incrementAndGet();
            if ("message-one".equals(metadata.get("af_message_id"))) {
                sharedMessageCalls.incrementAndGet();
                messageStarted.countDown();
                return releaseMessage.thenApply(ignored -> new PersistentMessage(
                        "input-" + sequence, threadId, null, role, text, attachments, metadata, Instant.now()));
            }
            return CompletableFuture.completedStage(new PersistentMessage(
                    "input-" + sequence, threadId, null, role, text, attachments, metadata, Instant.now()));
        }

        @Override
        public CompletionStage<PersistentPage<PersistentMessage>> listMessagesAsync(
                String threadId, String runId, int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentPage<>(
                    List.of(new PersistentMessage(
                            "response-" + runId,
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
            return CompletableFuture.completedStage(
                    run("run-" + runSequence.incrementAndGet(), request.threadId(), PersistentRunStatus.QUEUED));
        }

        @Override
        public CompletionStage<PersistentRun> getRunAsync(String threadId, String runId, RunCancellation cancellation) {
            return CompletableFuture.completedStage(run(runId, threadId, PersistentRunStatus.COMPLETED));
        }

        @Override
        public CompletionStage<PersistentPage<PersistentRun>> listRunsAsync(
                String threadId, int limit, String after, RunCancellation cancellation) {
            return CompletableFuture.completedStage(new PersistentPage<>(List.of(), null, false));
        }

        @Override
        public CompletionStage<PersistentRun> cancelRunAsync(
                String threadId, String runId, RunCancellation cancellation) {
            return CompletableFuture.completedStage(run(runId, threadId, PersistentRunStatus.CANCELLED));
        }

        @Override
        public CompletionStage<PersistentRun> submitToolOutputsAsync(
                String threadId, String runId, List<PersistentToolOutput> outputs, RunCancellation cancellation) {
            return CompletableFuture.completedStage(run(runId, threadId, PersistentRunStatus.IN_PROGRESS));
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
