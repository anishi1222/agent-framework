// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.foundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingOutcomeStatus;
import com.microsoft.agents.hosting.HostingPrincipal;
import com.microsoft.agents.hosting.HostingRegistry;
import com.microsoft.agents.hosting.HostingRequestContext;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.hosting.HostingRunRequest;
import com.microsoft.agents.providers.azureaipersistent.AzureAIPersistentAgent;
import com.microsoft.agents.providers.azureaipersistent.PersistentRunContinuation;
import com.microsoft.agents.providers.azureaipersistent.PersistentThread;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FoundryHostingBridgeTest {
    @Test
    void persistentRoute_shouldPartitionThreadsByPrincipalAndReuseAuthorizedSession() {
        HostingRegistry registry = new HostingRegistry();
        AzureAIPersistentAgent agent = mockAgent();
        java.util.concurrent.atomic.AtomicInteger threadSequence = new java.util.concurrent.atomic.AtomicInteger();
        when(agent.createServiceThreadAsync(anyMap(), any()))
                .thenAnswer(ignored -> CompletableFuture.completedStage(new PersistentThread(
                        threadSequence.incrementAndGet() == 1 ? "thread-one" : "thread-two", Instant.now(), Map.of())));
        when(agent.runOnThreadAsync(
                        anyString(),
                        anyList(),
                        org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                        any(),
                        any()))
                .thenAnswer(
                        invocation -> CompletableFuture.completedStage(completedResponse(invocation.getArgument(0))));
        try (FoundryHostingBridge bridge = new FoundryHostingBridge(registry, smallOptions());
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            bridge.registerPersistentAgent("persistent", agent);
            HostingRunRequest request = request("conversation-one");

            HostingOutcome first = dispatcher
                    .runAsync(context("principal-one", "tenant-one"), HostingRouteKind.AGENT, "persistent", request)
                    .toCompletableFuture()
                    .join();
            HostingOutcome second = dispatcher
                    .runAsync(context("principal-one", "tenant-one"), HostingRouteKind.AGENT, "persistent", request)
                    .toCompletableFuture()
                    .join();
            HostingOutcome isolated = dispatcher
                    .runAsync(context("principal-two", "tenant-one"), HostingRouteKind.AGENT, "persistent", request)
                    .toCompletableFuture()
                    .join();

            assertThat(first.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            assertThat(second.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            assertThat(isolated.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            verify(agent, times(2)).createServiceThreadAsync(anyMap(), any());
            ArgumentCaptor<String> threads = ArgumentCaptor.forClass(String.class);
            verify(agent, times(3))
                    .runOnThreadAsync(
                            threads.capture(),
                            anyList(),
                            org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                            any(),
                            any());
            assertThat(threads.getAllValues()).containsExactly("thread-one", "thread-one", "thread-two");
        }
    }

    @Test
    void continuation_shouldBeOpaqueOneTimeAndPrincipalBound() {
        HostingRegistry registry = new HostingRegistry();
        AzureAIPersistentAgent agent = mockAgent();
        when(agent.createServiceThreadAsync(anyMap(), any()))
                .thenReturn(
                        CompletableFuture.completedStage(new PersistentThread("thread-one", Instant.now(), Map.of())));
        when(agent.runOnThreadAsync(
                        anyString(),
                        anyList(),
                        org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                        any(),
                        any()))
                .thenReturn(CompletableFuture.completedStage(requiredActionResponse()));
        when(agent.continueRunAsync(any(PersistentRunContinuation.class), any()))
                .thenReturn(CompletableFuture.completedStage(completedResponse("thread-one")));
        try (FoundryHostingBridge bridge = new FoundryHostingBridge(registry, smallOptions());
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            bridge.registerPersistentAgent("persistent", agent);
            HostingRequestContext owner = context("principal-one", "tenant-one");
            HostingOutcome suspended = dispatcher
                    .runAsync(owner, HostingRouteKind.AGENT, "persistent", request("conversation-one"))
                    .toCompletableFuture()
                    .join();
            String handle = ((StateValue.StringValue)
                            resultMetadata(suspended).get(FoundryHostingBridge.RESUME_HANDLE_METADATA))
                    .value();

            HostingRequestContext attacker = context("principal-two", "tenant-one");
            assertThatThrownBy(() -> bridge.continuePersistentAsync(attacker, "persistent", handle, List.of(), false)
                            .toCompletableFuture()
                            .join())
                    .hasRootCauseInstanceOf(SecurityException.class);
            assertThat(bridge.continuationCount()).isOne();

            AgentResponse<Void> resumed = bridge.continuePersistentAsync(owner, "persistent", handle, List.of(), false)
                    .toCompletableFuture()
                    .join();

            assertThat(handle).hasSizeGreaterThan(32).doesNotContain("thread-one", "run-one");
            assertThat(resumed.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(bridge.continuationCount()).isZero();
            assertThat(bridge.continuePersistentAsync(owner, "persistent", handle, List.of(), false)
                            .toCompletableFuture())
                    .isCompletedExceptionally();
        }
    }

    @Test
    void continuePersistentAsync_shouldReturnFailedStageWhenDelegateThrowsSynchronouslyForEmptyOutputs() {
        HostingRegistry registry = new HostingRegistry();
        AzureAIPersistentAgent agent = mockAgent();
        when(agent.createServiceThreadAsync(anyMap(), any()))
                .thenReturn(
                        CompletableFuture.completedStage(new PersistentThread("thread-one", Instant.now(), Map.of())));
        when(agent.runOnThreadAsync(
                        anyString(),
                        anyList(),
                        org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                        any(),
                        any()))
                .thenReturn(CompletableFuture.completedStage(requiredActionResponse()));
        when(agent.continueRunAsync(any(PersistentRunContinuation.class), any()))
                .thenThrow(new IllegalStateException("transport failed synchronously"));
        try (FoundryHostingBridge bridge = new FoundryHostingBridge(registry, smallOptions());
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            bridge.registerPersistentAgent("persistent", agent);
            HostingRequestContext owner = context("principal-one", "tenant-one");
            HostingOutcome suspended = dispatcher
                    .runAsync(owner, HostingRouteKind.AGENT, "persistent", request("conversation-one"))
                    .toCompletableFuture()
                    .join();
            String handle = ((StateValue.StringValue)
                            resultMetadata(suspended).get(FoundryHostingBridge.RESUME_HANDLE_METADATA))
                    .value();
            java.util.concurrent.atomic.AtomicReference<CompletionStage<AgentResponse<Void>>> returned =
                    new java.util.concurrent.atomic.AtomicReference<>();

            assertThatCode(() ->
                            returned.set(bridge.continuePersistentAsync(owner, "persistent", handle, List.of(), true)))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> returned.get().toCompletableFuture().join())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("transport failed synchronously");
        }
    }

    @Test
    void persistentRoute_shouldRejectSubmittedMessageIdsBeyondConfiguredCapacity() {
        HostingRegistry registry = new HostingRegistry();
        AzureAIPersistentAgent agent = mockAgent();
        when(agent.createServiceThreadAsync(anyMap(), any()))
                .thenReturn(
                        CompletableFuture.completedStage(new PersistentThread("thread-one", Instant.now(), Map.of())));
        FoundryHostingOptions options =
                new FoundryHostingOptions(10, Duration.ofHours(1), 10, Duration.ofMinutes(5), 1, 3);
        Message first = Message.builder(Role.USER)
                .messageId("message-one")
                .contents(List.of(new com.microsoft.agents.core.TextContent("one")))
                .build();
        Message second = Message.builder(Role.USER)
                .messageId("message-two")
                .contents(List.of(new com.microsoft.agents.core.TextContent("two")))
                .build();
        HostingRunRequest request = new HostingRunRequest(
                List.of(first, second),
                null,
                RunOptions.empty(),
                Map.of(FoundryHostingBridge.CONVERSATION_ID_METADATA, StateValue.string("conversation-one")));
        try (FoundryHostingBridge bridge = new FoundryHostingBridge(registry, options);
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            bridge.registerPersistentAgent("persistent", agent);

            HostingOutcome outcome = dispatcher
                    .runAsync(context("principal-one", "tenant-one"), HostingRouteKind.AGENT, "persistent", request)
                    .toCompletableFuture()
                    .join();

            assertThat(outcome.status()).isEqualTo(HostingOutcomeStatus.FAILED);
            verify(agent, never())
                    .runOnThreadAsync(
                            anyString(),
                            anyList(),
                            org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                            any(),
                            any());
        }
    }

    @Test
    void persistentRoute_shouldDeleteLosingThreadAfterConcurrentCreateConflict() {
        HostingRegistry registry = new HostingRegistry();
        FoundryHostedSessionStore sessions = mock(FoundryHostedSessionStore.class);
        AzureAIPersistentAgent agent = mockAgent();
        FoundryHostedSessionKey key =
                new FoundryHostedSessionKey("persistent", "principal-one", "tenant-one", "conversation-one");
        FoundryHostedSession winner =
                new FoundryHostedSession(key, "thread-winner", null, 1, Instant.now(), Instant.now());
        when(sessions.loadAsync(key))
                .thenReturn(CompletableFuture.completedStage(java.util.Optional.empty()))
                .thenReturn(CompletableFuture.completedStage(java.util.Optional.of(winner)))
                .thenReturn(CompletableFuture.completedStage(java.util.Optional.of(winner)));
        when(sessions.saveAsync(any(), org.mockito.ArgumentMatchers.eq(FoundryHostedSession.CREATE_ONLY)))
                .thenReturn(CompletableFuture.failedStage(
                        new com.microsoft.agents.core.StorageConflictException("concurrent create")));
        when(sessions.saveAsync(any(), org.mockito.ArgumentMatchers.eq(1L)))
                .thenAnswer(invocation -> CompletableFuture.completedStage(invocation.getArgument(0)));
        when(agent.createServiceThreadAsync(anyMap(), any()))
                .thenReturn(CompletableFuture.completedStage(
                        new PersistentThread("thread-loser", Instant.now(), Map.of())));
        when(agent.deleteServiceThreadAsync(anyString(), any())).thenReturn(CompletableFuture.completedStage(null));
        when(agent.runOnThreadAsync(
                        anyString(),
                        anyList(),
                        org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                        any(),
                        any()))
                .thenReturn(CompletableFuture.completedStage(completedResponse("thread-winner")));
        try (FoundryHostingBridge bridge = new FoundryHostingBridge(registry, sessions, smallOptions());
                HostingDispatcher dispatcher = new HostingDispatcher(registry, HostingLimits.defaults())) {
            bridge.registerPersistentAgent("persistent", agent);

            HostingOutcome outcome = dispatcher
                    .runAsync(
                            context("principal-one", "tenant-one"),
                            HostingRouteKind.AGENT,
                            "persistent",
                            request("conversation-one"))
                    .toCompletableFuture()
                    .join();

            assertThat(outcome.status()).isEqualTo(HostingOutcomeStatus.COMPLETED);
            verify(agent).deleteServiceThreadAsync(org.mockito.ArgumentMatchers.eq("thread-loser"), any());
            verify(agent)
                    .runOnThreadAsync(
                            org.mockito.ArgumentMatchers.eq("thread-winner"),
                            anyList(),
                            org.mockito.ArgumentMatchers.<java.util.Set<String>>any(),
                            any(),
                            any());
        }
    }

    private static AzureAIPersistentAgent mockAgent() {
        AzureAIPersistentAgent agent = mock(AzureAIPersistentAgent.class);
        when(agent.metadata()).thenReturn(new AgentMetadata("persistent-agent", "Persistent", "test"));
        return agent;
    }

    private static AgentResponse<Void> completedResponse(String threadId) {
        return AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "done")))
                .responseId("run-one")
                .agentId("persistent-agent")
                .finishReason(FinishReason.STOP)
                .metadata(Map.of(
                        "azureAiPersistent.threadId", StateValue.string(threadId),
                        "azureAiPersistent.runId", StateValue.string("run-one")))
                .build();
    }

    private static AgentResponse<Void> requiredActionResponse() {
        return AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, "approval")))
                .responseId("run-one")
                .agentId("persistent-agent")
                .finishReason(FinishReason.TOOL_CALLS)
                .continuationToken(StateValue.object(Map.of("kind", StateValue.string("submit_tool_outputs"))))
                .metadata(Map.of(
                        "azureAiPersistent.threadId", StateValue.string("thread-one"),
                        "azureAiPersistent.runId", StateValue.string("run-one")))
                .build();
    }

    private static HostingRunRequest request(String conversationId) {
        return new HostingRunRequest(
                List.of(Message.text(Role.USER, "hello")),
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

    private static FoundryHostingOptions smallOptions() {
        return new FoundryHostingOptions(10, Duration.ofHours(1), 10, Duration.ofMinutes(5));
    }

    private static Map<String, StateValue> resultMetadata(HostingOutcome outcome) {
        StateValue.ObjectValue result = (StateValue.ObjectValue) outcome.result();
        StateValue.ObjectValue metadata =
                (StateValue.ObjectValue) result.values().get("metadata");
        return metadata.values();
    }
}
