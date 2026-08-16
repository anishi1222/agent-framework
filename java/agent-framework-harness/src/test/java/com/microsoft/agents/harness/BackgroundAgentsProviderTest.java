// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.InMemorySessionStore;
import com.microsoft.agents.agents.SessionKey;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundAgentsProviderTest {
    @Test
    void provider_shouldRunWaitContinueAndClearBackgroundTasks() {
        try (BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(new EchoAgent("researcher")))) {
            AgentSession session = new AgentSession("background-parent");
            Map<String, FunctionTool> tools =
                    tools(provider.provideAsync(HarnessTestContexts.request(session, "background-run"))
                            .toCompletableFuture()
                            .join());

            StateValue.ObjectValue started = (StateValue.ObjectValue) invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("RESEARCHER"),
                            "input",
                            StateValue.string("first"),
                            "description",
                            StateValue.string("Investigate")));
            int taskId = HarnessToolSupport.integer(started.require("task_id"), "task_id");
            StateValue waited = invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));
            StateValue result = invoke(
                    tools.get(BackgroundAgentsProvider.GET_RESULT_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(taskId)));

            assertThat(waited).isEqualTo(StateValue.integer(taskId));
            assertThat(result).isEqualTo(StateValue.string("first-done"));
            assertThat(provider.getIncompleteTasks(session)).isEmpty();

            invoke(
                    tools.get(BackgroundAgentsProvider.CONTINUE_TASK_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(taskId), "input", StateValue.string("second")));
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));
            assertThat(invoke(
                            tools.get(BackgroundAgentsProvider.GET_RESULT_TOOL_NAME),
                            Map.of("task_id", StateValue.integer(taskId))))
                    .isEqualTo(StateValue.string("second-done"));
            assertThat(invoke(
                            tools.get(BackgroundAgentsProvider.CLEAR_TASK_TOOL_NAME),
                            Map.of("task_id", StateValue.integer(taskId))))
                    .isEqualTo(StateValue.bool(true));
        }
    }

    @Test
    void provider_shouldMarkPersistedRunningTasksLostWhenRuntimeIsMissing() {
        AgentSession session = new AgentSession("lost-parent");
        try (BackgroundAgentsProvider first = new BackgroundAgentsProvider(List.of(new NeverCompletingAgent("slow")))) {
            FunctionTool start = tools(first.provideAsync(HarnessTestContexts.request(session, "lost-start"))
                            .toCompletableFuture()
                            .join())
                    .get(BackgroundAgentsProvider.START_TASK_TOOL_NAME);
            invoke(
                    start,
                    Map.of(
                            "agent_name",
                            StateValue.string("slow"),
                            "input",
                            StateValue.string("wait"),
                            "description",
                            StateValue.string("Long task")));
            assertThat(first.getIncompleteTasks(session)).hasSize(1);
        }

        try (BackgroundAgentsProvider restored = new BackgroundAgentsProvider(List.of(new EchoAgent("slow")))) {
            List<BackgroundTaskInfo> tasks = restored.getAllTasks(session);

            assertThat(tasks)
                    .singleElement()
                    .satisfies(task -> assertThat(task.status()).isEqualTo(BackgroundTaskStatus.LOST));
            assertThat(restored.getIncompleteTasks(session)).isEmpty();
        }
    }

    @Test
    void provider_shouldRejectContinuationWhenRestoredChildSessionIsMissing() {
        AgentSession session = new AgentSession("restored-completed-parent");
        int taskId;
        try (BackgroundAgentsProvider first = new BackgroundAgentsProvider(List.of(new EchoAgent("researcher")))) {
            Map<String, FunctionTool> tools =
                    tools(first.provideAsync(HarnessTestContexts.request(session, "completed-start"))
                            .toCompletableFuture()
                            .join());
            StateValue.ObjectValue started = (StateValue.ObjectValue) invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("first"),
                            "description",
                            StateValue.string("Completed task")));
            taskId = HarnessToolSupport.integer(started.require("task_id"), "task_id");
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));
        }

        try (BackgroundAgentsProvider restored = new BackgroundAgentsProvider(List.of(new EchoAgent("researcher")))) {
            FunctionTool continueTool = tools(
                            restored.provideAsync(HarnessTestContexts.request(session, "completed-continue"))
                                    .toCompletableFuture()
                                    .join())
                    .get(BackgroundAgentsProvider.CONTINUE_TASK_TOOL_NAME);

            assertThatThrownBy(() -> invoke(
                            continueTool,
                            Map.of("task_id", StateValue.integer(taskId), "input", StateValue.string("second"))))
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("child session was lost");
        }
    }

    @Test
    void providerClose_shouldCancelActiveChildRuns() throws Exception {
        CancellationRecordingAgent agent = new CancellationRecordingAgent("slow");
        BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(agent));
        AgentSession session = new AgentSession("close-cancellation-parent");
        FunctionTool start = tools(provider.provideAsync(HarnessTestContexts.request(session, "close-cancellation"))
                        .toCompletableFuture()
                        .join())
                .get(BackgroundAgentsProvider.START_TASK_TOOL_NAME);
        invoke(
                start,
                Map.of(
                        "agent_name",
                        StateValue.string("slow"),
                        "input",
                        StateValue.string("wait"),
                        "description",
                        StateValue.string("Cancelable task")));
        assertThat(agent.started.await(5, TimeUnit.SECONDS)).isTrue();

        provider.close();

        assertThat(agent.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(agent.cancellationObserved).isTrue();
        try (BackgroundAgentsProvider restored =
                new BackgroundAgentsProvider(List.of(new CancellationRecordingAgent("slow")))) {
            assertThat(restored.getAllTasks(session)).singleElement().satisfies(task -> {
                assertThat(task.status()).isEqualTo(BackgroundTaskStatus.LOST);
                assertThat(task.errorText()).isEqualTo("The process-local execution handle was closed.");
            });
        }
    }

    @Test
    void callerCancellation_shouldPersistTerminalCancelledStatus() throws Exception {
        CancellationRecordingAgent agent = new CancellationRecordingAgent("slow");
        try (BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(agent))) {
            AgentSession session = new AgentSession("caller-cancellation-parent");
            FunctionTool start = tools(
                            provider.provideAsync(HarnessTestContexts.request(session, "caller-cancellation"))
                                    .toCompletableFuture()
                                    .join())
                    .get(BackgroundAgentsProvider.START_TASK_TOOL_NAME);
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            invoke(
                    start,
                    Map.of(
                            "agent_name",
                            StateValue.string("slow"),
                            "input",
                            StateValue.string("wait"),
                            "description",
                            StateValue.string("Cancelled task")),
                    cancellation);
            assertThat(agent.started.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.cancel();
            assertThat(agent.cancelled.await(5, TimeUnit.SECONDS)).isTrue();

            assertEventually(() -> provider.getAllTasks(session).getFirst().status() == BackgroundTaskStatus.CANCELLED);
            assertThat(provider.getAllTasks(session).getFirst().errorText())
                    .isEqualTo("The background task was cancelled.");
        }
    }

    @Test
    void concurrentContinuations_shouldLaunchExactlyOneFollowUp() throws Exception {
        BlockingContinuationAgent agent = new BlockingContinuationAgent("researcher");
        try (BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(agent))) {
            AgentSession session = new AgentSession("concurrent-continuation-parent");
            Map<String, FunctionTool> tools =
                    tools(provider.provideAsync(HarnessTestContexts.request(session, "continuation-start"))
                            .toCompletableFuture()
                            .join());
            StateValue.ObjectValue started = (StateValue.ObjectValue) invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("first"),
                            "description",
                            StateValue.string("Concurrent continuation")));
            int taskId = HarnessToolSupport.integer(started.require("task_id"), "task_id");
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));

            List<CompletableFuture<Boolean>> attempts = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try {
                            invoke(
                                    tools.get(BackgroundAgentsProvider.CONTINUE_TASK_TOOL_NAME),
                                    Map.of(
                                            "task_id",
                                            StateValue.integer(taskId),
                                            "input",
                                            StateValue.string("follow-up-" + index)));
                            return true;
                        } catch (CompletionException | IllegalStateException expected) {
                            return false;
                        }
                    }))
                    .toList();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();

            assertThat(attempts.stream().filter(CompletableFuture::join).count())
                    .isEqualTo(1);
            assertThat(agent.continuationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(agent.calls).hasValue(2);
        }
    }

    @Test
    void concurrentClearAndContinuation_shouldCommitExactlyOneTransition() throws Exception {
        BlockingContinuationAgent agent = new BlockingContinuationAgent("researcher");
        try (BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(agent))) {
            AgentSession session = new AgentSession("clear-continuation-parent");
            Map<String, FunctionTool> tools =
                    tools(provider.provideAsync(HarnessTestContexts.request(session, "clear-continuation-start"))
                            .toCompletableFuture()
                            .join());
            StateValue.ObjectValue started = (StateValue.ObjectValue) invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("first"),
                            "description",
                            StateValue.string("Clear race")));
            int taskId = HarnessToolSupport.integer(started.require("task_id"), "task_id");
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));

            CountDownLatch startRace = new CountDownLatch(1);
            CompletableFuture<Boolean> continued = CompletableFuture.supplyAsync(() -> {
                await(startRace);
                try {
                    invoke(
                            tools.get(BackgroundAgentsProvider.CONTINUE_TASK_TOOL_NAME),
                            Map.of("task_id", StateValue.integer(taskId), "input", StateValue.string("follow-up")));
                    return true;
                } catch (RuntimeException expected) {
                    return false;
                }
            });
            CompletableFuture<Boolean> cleared = CompletableFuture.supplyAsync(() -> {
                await(startRace);
                try {
                    invoke(
                            tools.get(BackgroundAgentsProvider.CLEAR_TASK_TOOL_NAME),
                            Map.of("task_id", StateValue.integer(taskId)));
                    return true;
                } catch (RuntimeException expected) {
                    return false;
                }
            });
            startRace.countDown();
            CompletableFuture.allOf(continued, cleared).join();

            assertThat(continued.join()).isNotEqualTo(cleared.join());
            if (continued.join()) {
                assertThat(agent.continuationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(provider.getAllTasks(session))
                        .singleElement()
                        .satisfies(task -> assertThat(task.status()).isEqualTo(BackgroundTaskStatus.RUNNING));
            } else {
                assertThat(provider.getAllTasks(session)).isEmpty();
                assertThat(agent.calls).hasValue(1);
            }
        }
    }

    @Test
    void closeRacingBlockedStart_shouldNotLeaveRunningMetadata() throws Exception {
        NeverCompletingAgent agent = new NeverCompletingAgent("slow");
        BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(agent));
        AgentSession session = new AgentSession("close-start-parent");
        FunctionTool start = tools(provider.provideAsync(HarnessTestContexts.request(session, "close-start"))
                        .toCompletableFuture()
                        .join())
                .get(BackgroundAgentsProvider.START_TASK_TOOL_NAME);
        CountDownLatch stateLocked = new CountDownLatch(1);
        CountDownLatch releaseState = new CountDownLatch(1);
        Thread stateHolder = Thread.ofPlatform()
                .start(() -> session.updateState("test.lock", current -> {
                    stateLocked.countDown();
                    await(releaseState);
                    return StateValue.string("released");
                }));
        assertThat(stateLocked.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicReference<Throwable> startFailure = new AtomicReference<>();
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                invoke(
                        start,
                        Map.of(
                                "agent_name",
                                StateValue.string("slow"),
                                "input",
                                StateValue.string("wait"),
                                "description",
                                StateValue.string("Close race")));
            } catch (Throwable failure) {
                startFailure.set(failure);
            }
        });
        assertEventually(() -> starter.getState() == Thread.State.BLOCKED);
        Thread closer = Thread.ofPlatform().start(provider::close);

        releaseState.countDown();
        stateHolder.join(TimeUnit.SECONDS.toMillis(5));
        starter.join(TimeUnit.SECONDS.toMillis(5));
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(stateHolder.isAlive()).isFalse();
        assertThat(starter.isAlive()).isFalse();
        assertThat(closer.isAlive()).isFalse();
        assertThat(startFailure.get()).isNull();
        StateValue.ObjectValue persisted = HarnessToolSupport.object(
                session.state().get(provider.stateKey()).orElseThrow(), "background state");
        StateValue.ArrayValue tasks = (StateValue.ArrayValue) persisted.require("tasks");
        assertThat(tasks.values())
                .singleElement()
                .satisfies(
                        task -> assertThat(HarnessToolSupport.string(HarnessToolSupport.object(task, "task"), "status"))
                                .isEqualTo(BackgroundTaskStatus.LOST.name()));
    }

    @Test
    void backgroundChatAgent_shouldKeepChildSessionOutOfConfiguredStore() {
        InMemorySessionStore store = new InMemorySessionStore();
        ChatAgent childAgent = new ChatAgent(
                new EchoChatClient(),
                new AgentMetadata("background-chat", "researcher", null),
                ChatOptions.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                store);
        try (childAgent;
                BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(childAgent))) {
            AgentSession parent = new AgentSession("background-persistence-parent");
            Map<String, FunctionTool> tools =
                    tools(provider.provideAsync(HarnessTestContexts.request(parent, "background-persistence"))
                            .toCompletableFuture()
                            .join());
            StateValue.ObjectValue started = (StateValue.ObjectValue) invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("hello"),
                            "description",
                            StateValue.string("Transient child")));
            int taskId = HarnessToolSupport.integer(started.require("task_id"), "task_id");
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(taskId)))));

            assertThat(store.loadAsync(new SessionKey(parent.sessionId() + "-background-" + taskId))
                            .toCompletableFuture()
                            .join())
                    .isEmpty();
        }
    }

    private static Map<String, FunctionTool> tools(ContextContribution contribution) {
        return contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
    }

    private static StateValue invoke(FunctionTool tool, Map<String, StateValue> arguments) {
        return invoke(tool, arguments, new DefaultRunCancellation());
    }

    private static StateValue invoke(
            FunctionTool tool, Map<String, StateValue> arguments, RunCancellation cancellation) {
        long invocationSequence = System.nanoTime();
        ToolInvocationContext invocationContext = new ToolInvocationContext(
                "background-test",
                "call-" + tool.name() + "-" + invocationSequence,
                new InvocationId("background-test:" + tool.name() + ":" + invocationSequence),
                cancellation,
                Runnable::run,
                Map.of());
        return tool.invokeAsync(invocationContext, StateValue.object(arguments))
                .toCompletableFuture()
                .join();
    }

    private static final class EchoChatClient implements ChatClient {
        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            return CompletableFuture.completedFuture(ChatResponse.builder()
                    .messages(List.of(Message.text(
                            Role.ASSISTANT, request.messages().getLast().text() + "-done")))
                    .responseId("background-chat-response")
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public Flow.Publisher<com.microsoft.agents.core.ChatResponseUpdate> completeStreaming(
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

    private static final class EchoAgent implements Agent<Void> {
        private final AgentMetadata metadata;

        private EchoAgent(String name) {
            metadata = new AgentMetadata("agent-" + name, name, null);
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            CompletableFuture.runAsync(
                    () -> source.tryComplete(response(messages.getLast().text() + "-done")));
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
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

    private static final class NeverCompletingAgent implements Agent<Void> {
        private final AgentMetadata metadata;

        private NeverCompletingAgent(String name) {
            metadata = new AgentMetadata("agent-" + name, name, null);
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return new RunHandleSource<AgentResponse<Void>>(cancellation).handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class CancellationRecordingAgent implements Agent<Void> {
        private final AgentMetadata metadata;

        private final CountDownLatch started = new CountDownLatch(1);

        private final CountDownLatch cancelled = new CountDownLatch(1);

        private final AtomicBoolean cancellationObserved = new AtomicBoolean();

        private CancellationRecordingAgent(String name) {
            metadata = new AgentMetadata("agent-" + name, name, null);
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            started.countDown();
            cancellation.cancelledAsync().whenComplete((ignored, failure) -> {
                cancellationObserved.set(cancellation.isCancellationRequested());
                cancelled.countDown();
            });
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
        }
    }

    private static final class BlockingContinuationAgent implements Agent<Void> {
        private final AgentMetadata metadata;

        private final AtomicInteger calls = new AtomicInteger();

        private final CountDownLatch continuationStarted = new CountDownLatch(1);

        private BlockingContinuationAgent(String name) {
            metadata = new AgentMetadata("agent-" + name, name, null);
        }

        @Override
        public AgentMetadata metadata() {
            return metadata;
        }

        @Override
        public RunHandle<AgentResponse<Void>> startRun(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            int call = calls.incrementAndGet();
            RunHandleSource<AgentResponse<Void>> source = new RunHandleSource<>(cancellation);
            if (call == 1) {
                source.tryComplete(response("first-done"));
            } else {
                continuationStarted.countDown();
            }
            return source.handle();
        }

        @Override
        public Flow.Publisher<AgentResponseUpdate> runStreaming(
                List<Message> messages, RunOptions options, RunCancellation cancellation) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interruption);
        }
    }

    private static void assertEventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(
                    java.time.Duration.ofMillis(1).toNanos());
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static AgentResponse<Void> response(String text) {
        return AgentResponse.<Void>builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .responseId("background-response-" + text)
                .agentId("background-agent")
                .createdAt(Instant.EPOCH)
                .build();
    }
}
