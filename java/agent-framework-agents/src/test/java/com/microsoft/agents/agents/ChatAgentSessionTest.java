// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolApprovalState;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatAgentSessionTest {
    @Test
    void sessionRun_shouldFailConcurrentRunDeterministically_withoutLostUpdate() throws Exception {
        // Arrange
        CompletableFuture<ChatResponse> firstResponse = new CompletableFuture<>();
        FakeChatClient client = new FakeChatClient().enqueueFinite((request, cancellation) -> firstResponse);
        AgentSession session = new AgentSession("session-concurrent");

        // Act
        try (ChatAgent agent = new ChatAgent(client)) {
            CompletionStage<AgentRunResult<Void>> first = agent.runAsync(session, "first");
            assertThat(client.firstRequest().await(5, TimeUnit.SECONDS)).isTrue();

            // Assert
            assertThatThrownBy(() -> agent.runAsync(session, "second")).isInstanceOf(SessionBusyException.class);
            firstResponse.complete(response("first-answer"));
            assertThat(first.toCompletableFuture()
                            .join()
                            .response()
                            .orElseThrow()
                            .text())
                    .isEqualTo("first-answer");
        }
    }

    @Test
    void approval_shouldPersistRestoreResumeOnce_andRejectCrossSessionAndReplay() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        AgentRunResult<Void> suspended;
        AgentSession original;
        FakeChatClient firstClient = approvalConversation();
        try (ChatAgent firstAgent = configured(firstClient, tool, store)) {
            original = firstAgent.createSession();
            suspended =
                    firstAgent.runAsync(original, "write").toCompletableFuture().join();
            assertThat(suspended.outcome()).isEqualTo(AgentRunOutcome.INPUT_REQUIRED);
            assertThat(firstAgent.pendingContinuation(original)).isPresent();
            assertThat(store.loadAsync(SessionKey.of(original))
                            .toCompletableFuture()
                            .join()
                            .orElseThrow()
                            .snapshot()
                            .pendingRun())
                    .isNotNull();
            assertThat(firstAgent.activeLoopCountForDiagnostics()).isZero();
            assertThat(firstAgent.suspendedExecutionCountForDiagnostics()).isZero();
        }

        AgentContinuation continuation = suspended.continuation().orElseThrow();
        ToolApprovalDecision approval =
                ToolApprovalDecision.approve(continuation.approvalRequests().getFirst());
        FakeChatClient resumedClient = new FakeChatClient().enqueue(response("done"));
        try (ChatAgent resumedAgent = configured(resumedClient, tool, store)) {
            AgentSession restored = resumedAgent
                    .loadSessionAsync(original.sessionId())
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            AgentSession wrongSession = new AgentSession("other-session");

            assertThatThrownBy(() -> resumedAgent
                            .resumeAsync(wrongSession, continuation, List.of(approval))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(MiddlewareException.class);

            AgentRunResult<Void> completed = resumedAgent
                    .resumeAsync(restored, continuation, List.of(approval))
                    .toCompletableFuture()
                    .join();

            assertThat(completed.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
            assertThat(completed.response().orElseThrow().text()).contains("done");
            assertThat(invocations).hasValue(1);
            assertThat(resumedAgent.pendingContinuation(restored)).isEmpty();
            assertThatThrownBy(() -> resumedAgent
                            .resumeAsync(restored, continuation, List.of(approval))
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(MiddlewareException.class);
            assertThat(invocations).hasValue(1);
        }
    }

    @Test
    void rejectedApproval_shouldProduceCorrelatedToolResultWithoutInvokingTool() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        AgentSession session = new AgentSession("session-reject");
        FakeChatClient client = approvalConversation();

        // Act
        try (ChatAgent agent = configured(client, tool, null)) {
            AgentRunResult<Void> suspended =
                    agent.runAsync(session, "write").toCompletableFuture().join();
            AgentContinuation continuation = suspended.continuation().orElseThrow();
            ToolApprovalDecision rejection =
                    ToolApprovalDecision.reject(continuation.approvalRequests().getFirst(), "not allowed");
            AgentRunResult<Void> completed = agent.resumeAsync(session, continuation, List.of(rejection))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(completed.response().orElseThrow().messages().stream()
                            .flatMap(message -> message.contents().stream())
                            .filter(FunctionResultContent.class::isInstance)
                            .map(FunctionResultContent.class::cast))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.callId()).isEqualTo("call-approval");
                        assertThat(result.result()).isEqualTo(StateValue.string("The tool was not executed."));
                    });
        }
        assertThat(invocations).hasValue(0);
    }

    @Test
    void staleDigestDecision_shouldBeRejectedAndKeepAOneTimeContinuationPending() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        AgentSession session = new AgentSession("session-stale");
        try (ChatAgent agent = configured(approvalConversation(), approvalTool(invocations), null)) {
            AgentRunResult<Void> suspended =
                    agent.runAsync(session, "write").toCompletableFuture().join();
            AgentContinuation continuation = suspended.continuation().orElseThrow();
            var request = continuation.approvalRequests().getFirst();
            ToolApprovalDecision stale = new ToolApprovalDecision(
                    request.approvalId(), request.invocationId(), "wrong-digest", ToolApprovalState.APPROVED, null);

            // Act
            AgentRunResult<Void> rejected = agent.resumeAsync(session, continuation, List.of(stale))
                    .toCompletableFuture()
                    .join();

            // Assert
            assertThat(rejected.outcome()).isEqualTo(AgentRunOutcome.INPUT_REQUIRED);
            assertThat(rejected.rejectedDecisions())
                    .singleElement()
                    .satisfies(item -> assertThat(item.reason().name()).isEqualTo("MISMATCHED_REQUEST"));
            assertThat(rejected.continuation().orElseThrow().continuationId())
                    .isNotEqualTo(continuation.continuationId());
        }
        assertThat(invocations).hasValue(0);
    }

    @Test
    void sessionSaveFailure_shouldPropagateAndNeverSilentlyRetry() {
        // Arrange
        InMemorySessionStore delegate = new InMemorySessionStore();
        AtomicInteger saves = new AtomicInteger();
        SessionStore failing = new SessionStore() {
            @Override
            public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
                return delegate.loadAsync(key);
            }

            @Override
            public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
                    SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
                if (saves.incrementAndGet() > 1) {
                    return CompletableFuture.failedFuture(new StorageConflictException("forced save conflict"));
                }
                return delegate.saveAsync(key, snapshot, expectedRevision);
            }

            @Override
            public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
                return delegate.deleteAsync(key, expectedRevision);
            }

            @Override
            public SessionStoreDurability durability() {
                return SessionStoreDurability.PROCESS_MEMORY;
            }
        };
        FakeChatClient client = new FakeChatClient().enqueue(response("answer"));

        // Act and assert
        try (ChatAgent agent = configured(client, null, failing)) {
            AgentSession session = agent.createSession();
            assertThatThrownBy(() -> agent.runAsync(session, "hello")
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(StorageConflictException.class)
                    .hasMessageContaining("forced save conflict");
        }
        assertThat(saves).hasValue(2);
    }

    @Test
    void processLocalSession_shouldSkipAutomaticPersistenceButAllowExplicitSave() {
        InMemorySessionStore store = new InMemorySessionStore();
        AgentSession session = AgentSession.processLocal("process-local-session");
        FakeChatClient client = new FakeChatClient().enqueue(response("answer"));

        try (ChatAgent agent = configured(client, null, store)) {
            assertThat(agent.runAsync(session, "hello")
                            .toCompletableFuture()
                            .join()
                            .response())
                    .isPresent();
            assertThat(store.loadAsync(SessionKey.of(session))
                            .toCompletableFuture()
                            .join())
                    .isEmpty();

            agent.saveSessionAsync(session).toCompletableFuture().join();
            assertThat(store.loadAsync(SessionKey.of(session))
                            .toCompletableFuture()
                            .join())
                    .isPresent();
        }
    }

    @Test
    void processLocalApproval_shouldNotAdvertiseRestartPersistence() {
        InMemorySessionStore store = new InMemorySessionStore();
        AgentSession session = AgentSession.processLocal("process-local-approval");
        AtomicInteger invocations = new AtomicInteger();
        FakeChatClient client = approvalConversation().enqueue(response("done"));

        try (ChatAgent agent = configured(client, approvalTool(invocations), store)) {
            AgentRunResult<Void> suspended =
                    agent.runAsync(session, "write").toCompletableFuture().join();

            assertThat(suspended.continuation().orElseThrow().restartCapable()).isFalse();
            assertThat(store.loadAsync(SessionKey.of(session))
                            .toCompletableFuture()
                            .join())
                    .isEmpty();

            ToolApprovalDecision approval = ToolApprovalDecision.approve(
                    suspended.continuation().orElseThrow().approvalRequests().getFirst());
            AgentRunResult<Void> completed = agent.resumeAsync(
                            session, suspended.continuation().orElseThrow(), List.of(approval))
                    .toCompletableFuture()
                    .join();
            assertThat(completed.response().orElseThrow().text()).contains("done");
        }
        assertThat(invocations).hasValue(1);
    }

    @Test
    void pendingContinuationSerialization_shouldContainOnlySafeDataAndRoundTrip() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        FunctionTool tool = approvalTool(new AtomicInteger());
        AgentSession session;
        try (ChatAgent agent = configured(approvalConversation(), tool, store)) {
            session = agent.createSession();
            agent.runAsync(session, "write").toCompletableFuture().join();
        }
        AgentSessionCodec codec = new AgentSessionCodec(new JsonStateSerializer(
                SerializationLimits.defaults(),
                Map.of(
                        DocumentKind.AGENT_SESSION,
                        Set.of(1),
                        DocumentKind.HISTORY_MESSAGE,
                        Set.of(1),
                        DocumentKind.WORKFLOW_CHECKPOINT,
                        Set.of(1))));

        // Act
        byte[] encoded = codec.encode(session.snapshot());
        AgentSessionSnapshot decoded = codec.decode(encoded);
        String json = new String(encoded, StandardCharsets.UTF_8);

        // Assert
        assertThat(decoded.pendingRun())
                .isNotNull()
                .isEqualTo(session.snapshot().pendingRun());
        assertThat(json)
                .contains("chat-agent-continuation", "requestDigest", "logicalRunId")
                .doesNotContain("FunctionToolHandler", "ChatClient", "Executor", "Middleware", "credential", "@class");
    }

    @Test
    void durableSuspensions_shouldNotRetainOriginatingAgentResources_whenAbandonedOrRemotelyResumed() {
        // Arrange
        InMemorySessionStore store = new InMemorySessionStore();
        AtomicInteger invocations = new AtomicInteger();
        FunctionTool tool = approvalTool(invocations);
        FakeChatClient originClient = new FakeChatClient()
                .enqueue(approvalResponse())
                .enqueue(approvalResponse())
                .enqueue(approvalResponse());
        ChatAgent origin = configured(originClient, tool, store);

        // Act and assert
        for (int index = 0; index < 3; index++) {
            AgentSession session = origin.createSession();
            AgentRunResult<Void> suspended = origin.runAsync(session, "write-" + index)
                    .toCompletableFuture()
                    .join();
            assertThat(suspended.outcome()).isEqualTo(AgentRunOutcome.INPUT_REQUIRED);
            assertThat(origin.activeLoopCountForDiagnostics()).isZero();
            assertThat(origin.suspendedExecutionCountForDiagnostics()).isZero();

            if (index != 1) {
                try (ChatAgent remote =
                        configured(new FakeChatClient().enqueue(response("done-" + index)), tool, store)) {
                    AgentSession restored = remote.loadSessionAsync(session.sessionId())
                            .toCompletableFuture()
                            .join()
                            .orElseThrow();
                    AgentContinuation continuation = suspended.continuation().orElseThrow();
                    AgentRunResult<Void> completed = remote.resumeAsync(
                                    restored,
                                    continuation,
                                    List.of(ToolApprovalDecision.approve(
                                            continuation.approvalRequests().getFirst())))
                            .toCompletableFuture()
                            .join();
                    assertThat(completed.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
                }
            }

            assertThat(origin.activeLoopCountForDiagnostics()).isZero();
            assertThat(origin.suspendedExecutionCountForDiagnostics()).isZero();
        }

        origin.close();
        assertThatCode(origin::close).doesNotThrowAnyException();
        assertThat(origin.activeLoopCountForDiagnostics()).isZero();
        assertThat(origin.suspendedExecutionCountForDiagnostics()).isZero();
        assertThat(invocations).hasValue(2);
    }

    @Test
    void processLocalSuspension_shouldRetainOnlyResumableExecution_andReleaseItAfterResume() {
        // Arrange
        AtomicInteger invocations = new AtomicInteger();
        FakeChatClient client = approvalConversation().enqueue(response("done"));
        ChatAgent agent = configured(client, approvalTool(invocations), null);
        ApprovalRequiredException required;
        try {
            agent.runAsync("write").toCompletableFuture().join();
            throw new AssertionError("Expected approval continuation.");
        } catch (CompletionException failure) {
            required = (ApprovalRequiredException) failure.getCause();
        }
        assertThat(agent.activeLoopCountForDiagnostics()).isOne();
        assertThat(agent.suspendedExecutionCountForDiagnostics()).isOne();

        // Act
        AgentRunResult<Void> completed = agent.resumeAsync(
                        required.continuation(),
                        List.of(ToolApprovalDecision.approve(
                                required.continuation().approvalRequests().getFirst())))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(completed.outcome()).isEqualTo(AgentRunOutcome.COMPLETED);
        assertThat(invocations).hasValue(1);
        assertThat(agent.activeLoopCountForDiagnostics()).isZero();
        assertThat(agent.suspendedExecutionCountForDiagnostics()).isZero();
        agent.close();
        assertThatCode(agent::close).doesNotThrowAnyException();
    }

    @Test
    void durableSuspensionPersistFailure_shouldRestoreUsableSessionAndReleaseLocalResources() {
        // Arrange
        InMemorySessionStore delegate = new InMemorySessionStore();
        AtomicInteger saves = new AtomicInteger();
        SessionStore failSecondSave = new SessionStore() {
            @Override
            public CompletionStage<Optional<VersionedSnapshot<AgentSessionSnapshot>>> loadAsync(SessionKey key) {
                return delegate.loadAsync(key);
            }

            @Override
            public CompletionStage<VersionedSnapshot<AgentSessionSnapshot>> saveAsync(
                    SessionKey key, AgentSessionSnapshot snapshot, long expectedRevision) {
                if (saves.incrementAndGet() == 2) {
                    return CompletableFuture.failedFuture(new StorageConflictException("forced suspension failure"));
                }
                return delegate.saveAsync(key, snapshot, expectedRevision);
            }

            @Override
            public CompletionStage<Void> deleteAsync(SessionKey key, long expectedRevision) {
                return delegate.deleteAsync(key, expectedRevision);
            }

            @Override
            public SessionStoreDurability durability() {
                return SessionStoreDurability.PROCESS_MEMORY;
            }
        };
        FakeChatClient client = new FakeChatClient().enqueue(approvalResponse()).enqueue(approvalResponse());

        // Act and assert
        try (ChatAgent agent = configured(client, approvalTool(new AtomicInteger()), failSecondSave)) {
            AgentSession session = agent.createSession();
            assertThatThrownBy(() -> agent.runAsync(session, "first")
                            .toCompletableFuture()
                            .join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(StorageConflictException.class)
                    .hasMessageContaining("forced suspension failure");
            assertThat(agent.pendingContinuation(session)).isEmpty();
            assertThat(agent.activeLoopCountForDiagnostics()).isZero();
            assertThat(agent.suspendedExecutionCountForDiagnostics()).isZero();

            AgentRunResult<Void> retry =
                    agent.runAsync(session, "retry").toCompletableFuture().join();
            assertThat(retry.outcome()).isEqualTo(AgentRunOutcome.INPUT_REQUIRED);
            assertThat(agent.pendingContinuation(session)).isPresent();
            assertThat(agent.activeLoopCountForDiagnostics()).isZero();
            assertThat(agent.suspendedExecutionCountForDiagnostics()).isZero();
        }
        assertThat(saves).hasValue(3);
    }

    @Test
    void close_shouldReleaseProcessLocalContinuationResources() {
        // Arrange
        FunctionTool tool = approvalTool(new AtomicInteger());
        ChatAgent agent = configured(approvalConversation(), tool, null);
        ApprovalRequiredException required;
        try {
            agent.runAsync("write").toCompletableFuture().join();
            throw new AssertionError("Expected approval continuation.");
        } catch (CompletionException failure) {
            required = (ApprovalRequiredException) failure.getCause();
        }

        // Act
        agent.close();

        // Assert
        assertThatThrownBy(() -> agent.resumeAsync(
                                required.continuation(),
                                List.of(ToolApprovalDecision.approve(required.continuation()
                                        .approvalRequests()
                                        .getFirst())))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(MiddlewareException.class)
                .hasMessageContaining("stale");
    }

    private static ChatAgent configured(FakeChatClient client, FunctionTool tool, SessionStore store) {
        return new ChatAgent(
                client,
                new AgentMetadata("agent-session-test", "test", null),
                ChatOptions.empty(),
                tool == null ? List.of() : List.of(tool),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                store);
    }

    private static FunctionTool approvalTool(AtomicInteger invocations) {
        ToolMetadata metadata = new ToolMetadata(
                "write",
                "test tool",
                Set.of(ToolCapability.FUNCTION),
                ToolApprovalMode.ALWAYS_REQUIRE,
                StateValue.object(Map.of("type", StateValue.string("object"))),
                StateValue.object(Map.of()));
        return FunctionTool.create(metadata, (context, arguments) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(StateValue.string("written"));
        });
    }

    private static FakeChatClient approvalConversation() {
        return new FakeChatClient().enqueue(approvalResponse());
    }

    private static ChatResponse approvalResponse() {
        FunctionCallContent call = new FunctionCallContent("call-approval", "write", StateValue.object(Map.of()));
        return ChatResponse.builder()
                .messages(List.of(new Message(Role.ASSISTANT, List.of(call))))
                .finishReason(FinishReason.TOOL_CALLS)
                .build();
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .finishReason(FinishReason.STOP)
                .build();
    }
}
