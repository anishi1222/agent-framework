// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ChatClientRequest;
import com.microsoft.agents.agents.ContextContribution;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.InMemoryHistoryProvider;
import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceAssertions;
import com.microsoft.agents.conformance.ConformanceFixtureCatalog;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.harness.files.FileSystemAgentFileStore;
import com.microsoft.agents.harness.files.InMemoryAgentFileStore;
import com.microsoft.agents.harness.files.StorePaths;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolInvocationContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessConformanceTest {
    private final ConformanceFixtureCatalog catalog = new ConformanceFixtureLoader().loadDefault();

    @TempDir
    Path temporaryDirectory;

    @Test
    void jcfHarness001_shouldBindAssemblyLoopStreamingAndJudgeContracts() {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-HARNESS-001");
        List<String> defaultProviders;
        try (HarnessAgent agent = new HarnessAgent(
                new SequenceChatClient(),
                HarnessAgentOptions.builder()
                        .fileMemoryStore(new InMemoryAgentFileStore())
                        .build())) {
            defaultProviders = providerNames(agent.contextProviders());
        }

        List<String> optInProviders;
        try (HarnessAgent agent = new HarnessAgent(
                new SequenceChatClient(),
                HarnessAgentOptions.builder()
                        .disableTodo(true)
                        .disableMode(true)
                        .disableFileMemory(true)
                        .fileAccessStore(new InMemoryAgentFileStore())
                        .backgroundAgents(List.of(new EchoAgent("researcher")))
                        .build())) {
            optInProviders = providerNames(agent.contextProviders());
        }

        AtomicInteger secondaryEvaluatorCalls = new AtomicInteger();
        LoopEvaluator primary = (context, cancellation) -> CompletableFuture.completedFuture(
                context.iteration() < 3
                        ? LoopEvaluation.continueWithFeedback("continue-" + (context.iteration() + 1))
                        : LoopEvaluation.stop());
        LoopEvaluator secondary = (context, cancellation) -> {
            secondaryEvaluatorCalls.incrementAndGet();
            return CompletableFuture.completedFuture(LoopEvaluation.stop());
        };
        LoopAgentOptions loopOptions = LoopAgentOptions.builder()
                .maxIterations(3)
                .returnFinalOnly(true)
                .build();

        SequenceChatClient finiteClient = new SequenceChatClient();
        AgentResponse<Void> finite;
        try (LoopAgent loop = new LoopAgent(chatAgent(finiteClient), List.of(primary, secondary), loopOptions, true)) {
            finite = loop.run("start");
        }

        SequenceChatClient streamingClient = new SequenceChatClient();
        List<AgentResponseUpdate> stream;
        try (LoopAgent loop =
                new LoopAgent(chatAgent(streamingClient), List.of(primary, secondary), loopOptions, true)) {
            stream = collect(loop.runStreaming("start"));
        }

        RecordingJudgeClient judge = new RecordingJudgeClient(response("VERDICT: DONE and VERDICT: MORE"));
        AIJudgeLoopEvaluator judgeEvaluator = new AIJudgeLoopEvaluator(judge);
        LoopEvaluation judgeEvaluation;
        try (ChatAgent contextAgent = chatAgent(new SequenceChatClient())) {
            judgeEvaluation = judgeEvaluator
                    .evaluateAsync(loopContext(contextAgent), new DefaultRunCancellation())
                    .toCompletableFuture()
                    .join();
        }

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("defaultProviderOrder", strings(defaultProviders));
        actual.put("optInProviderOrder", strings(optInProviders));
        actual.put("loopInvocationCount", number(streamingClient.calls()));
        actual.put(
                "streamSequence",
                strings(stream.stream().map(AgentResponseUpdate::text).toList()));
        actual.put("firstContinuingEvaluatorWins", bool(secondaryEvaluatorCalls.get() == 0));
        actual.put("finiteFinalOnlyMessageCount", number(finite.messages().size()));
        actual.put("streamingIgnoresFiniteFinalOnly", bool(stream.size() == 5));
        actual.put(
                "judgeStructuredOutputRequested",
                bool(judge.request.get().options().structuredOutput() != null));
        actual.put("judgeMoreMarkerWins", bool(judgeEvaluation.shouldContinue()));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfHarness002_shouldBindBackgroundLifecycleAndRestoration() {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-HARNESS-002");
        AgentSession session = new AgentSession("background-conformance");
        int firstId;
        int secondId;
        StateValue firstResult;
        StateValue continuedResult;
        StateValue waited;
        StateValue cleared;
        try (BackgroundAgentsProvider provider = new BackgroundAgentsProvider(List.of(new EchoAgent("researcher")))) {
            Map<String, FunctionTool> tools =
                    tools(provider.provideAsync(HarnessTestContexts.request(session, "background-conformance-run"))
                            .toCompletableFuture()
                            .join());
            firstId = taskId(invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("first"),
                            "description",
                            StateValue.string("First task"))));
            waited = invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(firstId)))));
            firstResult = invoke(
                    tools.get(BackgroundAgentsProvider.GET_RESULT_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(firstId)));
            invoke(
                    tools.get(BackgroundAgentsProvider.CONTINUE_TASK_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(firstId), "input", StateValue.string("second")));
            invoke(
                    tools.get(BackgroundAgentsProvider.WAIT_TOOL_NAME),
                    Map.of("task_ids", StateValue.array(List.of(StateValue.integer(firstId)))));
            continuedResult = invoke(
                    tools.get(BackgroundAgentsProvider.GET_RESULT_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(firstId)));
            cleared = invoke(
                    tools.get(BackgroundAgentsProvider.CLEAR_TASK_TOOL_NAME),
                    Map.of("task_id", StateValue.integer(firstId)));
            secondId = taskId(invoke(
                    tools.get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("third"),
                            "description",
                            StateValue.string("Second task"))));
        }

        AgentSession lostSession = new AgentSession("background-lost");
        try (BackgroundAgentsProvider first =
                new BackgroundAgentsProvider(List.of(new NeverCompletingAgent("researcher")))) {
            invoke(
                    tools(first.provideAsync(HarnessTestContexts.request(lostSession, "background-lost-run"))
                                    .toCompletableFuture()
                                    .join())
                            .get(BackgroundAgentsProvider.START_TASK_TOOL_NAME),
                    Map.of(
                            "agent_name",
                            StateValue.string("researcher"),
                            "input",
                            StateValue.string("wait"),
                            "description",
                            StateValue.string("Lost task")));
        }
        boolean restoredLost;
        boolean lostExcluded;
        try (BackgroundAgentsProvider restored = new BackgroundAgentsProvider(List.of(new EchoAgent("researcher")))) {
            restoredLost = restored.getAllTasks(lostSession).getFirst().status() == BackgroundTaskStatus.LOST;
            lostExcluded = restored.getIncompleteTasks(lostSession).isEmpty();
        }

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put("taskIdsMonotonic", bool(secondId > firstId));
        actual.put("waitReturnsCompletedTask", bool(waited.equals(StateValue.integer(firstId))));
        actual.put("firstResult", stringValue(firstResult));
        actual.put("continuedResult", stringValue(continuedResult));
        actual.put("terminalTaskClearable", bool(cleared.equals(StateValue.bool(true))));
        actual.put("restoredRunningBecomesLost", bool(restoredLost));
        actual.put("lostTaskExcludedFromIncomplete", bool(lostExcluded));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    @Test
    void jcfHarness003_shouldBindWorkspaceStateAndFilesystemSafety() throws IOException {
        BehaviorFixture fixture = (BehaviorFixture) catalog.requireCase("JCF-HARNESS-003");
        InMemoryAgentFileStore sharedStore = new InMemoryAgentFileStore();
        FileAccessProvider fileAccess = new FileAccessProvider(sharedStore);
        Map<String, FunctionTool> accessTools = tools(fileAccess
                .provideAsync(HarnessTestContexts.request(new AgentSession("shared-files"), "shared-files-run"))
                .toCompletableFuture()
                .join());
        invoke(
                accessTools.get(FileAccessProvider.WRITE_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared/data.txt"),
                        "content",
                        StateValue.string("alpha\nbeta"),
                        "overwrite",
                        StateValue.bool(false)));
        invoke(
                accessTools.get(FileAccessProvider.REPLACE_LINES_TOOL_NAME),
                Map.of(
                        "path",
                        StateValue.string("shared/data.txt"),
                        "edits",
                        StateValue.array(List.of(StateValue.object(Map.of(
                                "line_number", StateValue.integer(2), "new_line", StateValue.string("BETA")))))));
        StateValue edited = invoke(
                accessTools.get(FileAccessProvider.READ_TOOL_NAME),
                Map.of("path", StateValue.string("shared/data.txt")));

        InMemoryAgentFileStore memoryStore = new InMemoryAgentFileStore();
        FileMemoryProvider memory = new FileMemoryProvider(memoryStore);
        AgentSession firstMemorySession = new AgentSession("memory-first");
        AgentSession secondMemorySession = new AgentSession("memory-second");
        Map<String, FunctionTool> firstMemoryTools =
                tools(memory.provideAsync(HarnessTestContexts.request(firstMemorySession, "memory-first-run"))
                        .toCompletableFuture()
                        .join());
        invoke(
                firstMemoryTools.get(FileMemoryProvider.WRITE_TOOL_NAME),
                Map.of(
                        "file_name",
                        StateValue.string("plan.md"),
                        "content",
                        StateValue.string("step one"),
                        "description",
                        StateValue.string("Execution plan")));
        ContextContribution memoryIndex = memory.provideAsync(
                        HarnessTestContexts.request(firstMemorySession, "memory-index-run"))
                .toCompletableFuture()
                .join();
        StateValue secondMemoryFiles = invoke(
                tools(memory.provideAsync(HarnessTestContexts.request(secondMemorySession, "memory-second-run"))
                                .toCompletableFuture()
                                .join())
                        .get(FileMemoryProvider.LIST_TOOL_NAME),
                Map.of());
        boolean nestedMemoryRejected = rejectsIllegalArgument(() -> invoke(
                firstMemoryTools.get(FileMemoryProvider.WRITE_TOOL_NAME),
                Map.of("file_name", StateValue.string("nested/secret.md"), "content", StateValue.string("unsafe"))));

        TodoProvider todo = new TodoProvider();
        AgentSession todoSession = new AgentSession("todo-conformance");
        Map<String, FunctionTool> todoTools =
                tools(todo.provideAsync(HarnessTestContexts.request(todoSession, "todo-conformance-run"))
                        .toCompletableFuture()
                        .join());
        invoke(
                todoTools.get(TodoProvider.ADD_TOOL_NAME),
                Map.of(
                        "todos",
                        StateValue.array(List.of(
                                StateValue.object(Map.of("title", StateValue.string("first"))),
                                StateValue.object(Map.of("title", StateValue.string("second")))))));
        invoke(
                todoTools.get(TodoProvider.COMPLETE_TOOL_NAME),
                Map.of(
                        "items",
                        StateValue.array(List.of(StateValue.object(
                                Map.of("id", StateValue.integer(1), "reason", StateValue.string("done")))))));
        List<TodoItem> todos = todo.getAllTodosAsync(todoSession, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        List<TodoItem> remaining = todo.getRemainingTodosAsync(todoSession, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();

        AgentModeProvider mode = new AgentModeProvider();
        AgentSession modeSession = new AgentSession("mode-conformance");
        mode.getModeAsync(modeSession, new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        String normalizedMode = mode.setModeAsync(modeSession, " EXECUTE ", new DefaultRunCancellation())
                .toCompletableFuture()
                .join();
        ContextContribution firstModeNotice = mode.provideAsync(
                        HarnessTestContexts.request(modeSession, "mode-notice-first"))
                .toCompletableFuture()
                .join();
        ContextContribution secondModeNotice = mode.provideAsync(
                        HarnessTestContexts.request(modeSession, "mode-notice-second"))
                .toCompletableFuture()
                .join();

        boolean traversalRejected = rejectsIllegalArgument(() -> StorePaths.normalizeFilePath("../escape"));
        boolean symlinkRejected = filesystemSymlinkRejected();

        LinkedHashMap<String, ConformanceValue> actual = new LinkedHashMap<>();
        actual.put(
                "fileAccessApprovalRequired",
                bool(accessTools.values().stream()
                        .allMatch(tool -> tool.metadata().approvalMode() == ToolApprovalMode.ALWAYS_REQUIRE)));
        actual.put("fileAccessEditResult", stringValue(edited));
        actual.put("memorySessionIsolated", bool(secondMemoryFiles.equals(StateValue.array(List.of()))));
        actual.put(
                "memoryIndexDescribesVisibleFiles",
                bool(memoryIndex.messages().size() == 1
                        && memoryIndex.messages().getFirst().text().contains("plan.md")
                        && memoryIndex.messages().getFirst().text().contains("Execution plan")));
        actual.put("nestedMemoryRejected", bool(nestedMemoryRejected));
        actual.put(
                "todoIdsUnique",
                bool(todos.stream().map(TodoItem::id).distinct().count() == todos.size()));
        actual.put("completedTodoExcludedFromRemaining", bool(remaining.stream().noneMatch(item -> item.id() == 1)));
        actual.put("normalizedMode", new ConformanceValue.StringValue(normalizedMode));
        actual.put(
                "externalModeNotificationOneShot",
                bool(firstModeNotice.messages().size() == 1
                        && secondModeNotice.messages().isEmpty()));
        actual.put("filesystemTraversalRejected", bool(traversalRejected));
        actual.put("filesystemSymlinkRejected", bool(symlinkRejected));

        ConformanceAssertions.assertExpected(fixture, new ConformanceValue.ObjectValue(actual));
    }

    private boolean filesystemSymlinkRejected() throws IOException {
        Path root = temporaryDirectory.resolve("store");
        try (FileSystemAgentFileStore store = new FileSystemAgentFileStore(root)) {
            Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
            try {
                Files.createSymbolicLink(root.resolve("linked"), outside);
            } catch (UnsupportedOperationException | IOException unavailable) {
                return true;
            }
            return rejectsIllegalArgument(
                    () -> store.writeAsync("linked/secret.txt", "secret", new DefaultRunCancellation())
                            .toCompletableFuture()
                            .join());
        }
    }

    private static LoopContext loopContext(ChatAgent agent) {
        return new LoopContext(
                agent,
                new AgentSession("judge-context"),
                List.of(Message.text(Role.USER, "Complete the task.")),
                AgentResponse.<Void>builder()
                        .messages(List.of(Message.text(Role.ASSISTANT, "Partial response.")))
                        .responseId("judge-context-response")
                        .agentId(agent.metadata().id())
                        .createdAt(Instant.EPOCH)
                        .finishReason(FinishReason.STOP)
                        .build(),
                RunOptions.empty(),
                1,
                List.of(),
                List.of(),
                new java.util.concurrent.ConcurrentHashMap<>());
    }

    private static List<String> providerNames(List<ContextProvider> providers) {
        return providers.stream()
                .map(provider -> {
                    if (provider instanceof InMemoryHistoryProvider) {
                        return "history";
                    }
                    if (provider instanceof TodoProvider) {
                        return "todo";
                    }
                    if (provider instanceof AgentModeProvider) {
                        return "mode";
                    }
                    if (provider instanceof FileMemoryProvider) {
                        return "fileMemory";
                    }
                    if (provider instanceof FileAccessProvider) {
                        return "fileAccess";
                    }
                    if (provider instanceof BackgroundAgentsProvider) {
                        return "backgroundAgents";
                    }
                    return provider.id();
                })
                .toList();
    }

    private static ChatAgent chatAgent(ChatClient client) {
        return new ChatAgent(
                client,
                new AgentMetadata("harness-conformance-agent", "Harness", null),
                ChatOptions.empty(),
                List.of(),
                List.of(new InMemoryHistoryProvider()),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static Map<String, FunctionTool> tools(ContextContribution contribution) {
        return contribution.tools().stream()
                .map(FunctionTool.class::cast)
                .collect(java.util.stream.Collectors.toMap(FunctionTool::name, tool -> tool));
    }

    private static StateValue invoke(FunctionTool tool, Map<String, StateValue> arguments) {
        ToolInvocationContext invocation = new ToolInvocationContext(
                "harness-conformance",
                "call-" + tool.name() + "-" + CALL_IDS.incrementAndGet(),
                new InvocationId("harness-conformance:" + tool.name() + ":" + CALL_IDS.get()),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());
        return tool.invokeAsync(invocation, StateValue.object(arguments))
                .toCompletableFuture()
                .join();
    }

    private static int taskId(StateValue value) {
        return HarnessToolSupport.integer(((StateValue.ObjectValue) value).require("task_id"), "task_id");
    }

    private static boolean rejectsIllegalArgument(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause instanceof IllegalArgumentException;
        }
    }

    private static List<AgentResponseUpdate> collect(Flow.Publisher<AgentResponseUpdate> publisher) {
        CopyOnWriteArrayList<AgentResponseUpdate> updates = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> terminal = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
                updates.add(item);
            }

            @Override
            public void onError(Throwable failure) {
                terminal.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                terminal.complete(null);
            }
        });
        terminal.join();
        return List.copyOf(updates);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .messages(List.of(Message.text(Role.ASSISTANT, text)))
                .responseId("judge-response")
                .createdAt(Instant.EPOCH)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static ConformanceValue.BooleanValue bool(boolean value) {
        return new ConformanceValue.BooleanValue(value);
    }

    private static ConformanceValue.NumberValue number(long value) {
        return new ConformanceValue.NumberValue(BigDecimal.valueOf(value));
    }

    private static ConformanceValue.StringValue stringValue(StateValue value) {
        return new ConformanceValue.StringValue(((StateValue.StringValue) value).value());
    }

    private static ConformanceValue.ArrayValue strings(List<String> values) {
        return new ConformanceValue.ArrayValue(values.stream()
                .map(ConformanceValue.StringValue::new)
                .map(ConformanceValue.class::cast)
                .toList());
    }

    private static final AtomicInteger CALL_IDS = new AtomicInteger();

    private static final class SequenceChatClient implements ChatClient {
        private final AtomicInteger calls = new AtomicInteger();

        private int calls() {
            return calls.get();
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ChatClientRequest request, RunCancellation cancellation) {
            int iteration = calls.incrementAndGet();
            return CompletableFuture.completedFuture(response("iteration-" + iteration));
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ChatClientRequest request, RunCancellation cancellation) {
            int iteration = calls.incrementAndGet();
            ChatResponseUpdate update = ChatResponseUpdate.builder()
                    .sequence(0)
                    .contents(List.of(new TextContent("iteration-" + iteration)))
                    .role(Role.ASSISTANT)
                    .responseId("stream-response-" + iteration)
                    .messageId("stream-message-" + iteration)
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build();
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean signalled;

                @Override
                public void request(long count) {
                    if (signalled) {
                        return;
                    }
                    signalled = true;
                    subscriber.onNext(update);
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    signalled = true;
                }
            });
        }
    }

    private static final class RecordingJudgeClient implements ChatClient {
        private final ChatResponse response;

        private final AtomicReference<ChatClientRequest> request = new AtomicReference<>();

        private RecordingJudgeClient(ChatResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(
                ChatClientRequest nextRequest, RunCancellation cancellation) {
            request.set(nextRequest);
            return CompletableFuture.completedFuture(response);
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
            CompletableFuture.runAsync(() -> source.tryComplete(AgentResponse.<Void>builder()
                    .messages(List.of(
                            Message.text(Role.ASSISTANT, messages.getLast().text() + "-done")))
                    .responseId("background-response")
                    .agentId(metadata.id())
                    .createdAt(Instant.EPOCH)
                    .finishReason(FinishReason.STOP)
                    .build()));
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
}
