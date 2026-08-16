// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.AgentRunResult;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.agents.context.CompactingHistoryProvider;
import com.microsoft.agents.agents.context.ContextWindowCompactionStrategy;
import com.microsoft.agents.agents.skills.FileSkillsSourceOptions;
import com.microsoft.agents.agents.skills.SkillsProvider;
import com.microsoft.agents.agents.skills.SkillsProviderOptions;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.harness.files.AgentFileStore;
import com.microsoft.agents.harness.files.FileSystemAgentFileStore;
import com.microsoft.agents.tools.ToolApprovalDecision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Assembles the autonomous agent harness from provider-neutral Java runtime components.
 *
 * <p>Todo, mode, and isolated file memory are enabled by default. Shared file access, skills,
 * background agents, and autonomous looping are opt-in.
 */
public final class HarnessAgent implements Agent<Void> {
    /** Built-in general harness guidance. */
    public static final String DEFAULT_INSTRUCTIONS = """
            Think before acting and use tools when they materially improve correctness.
            Break complex work into explicit steps, keep durable notes and todos current, and adapt
            when an approach fails instead of repeating it blindly.
            Explain meaningful decisions, avoid long unexplained tool-call sequences, and finish
            with a clear result or a precise blocker.""";

    private final ChatAgent chatAgent;

    private final LoopAgent loopAgent;

    private final Agent<Void> runtime;

    private final List<ContextProvider> contextProviders;

    private final List<AutoCloseable> ownedResources;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a harness with default options.
     *
     * @param chatClient caller-owned provider-neutral chat client
     */
    public HarnessAgent(ChatClient chatClient) {
        this(chatClient, HarnessAgentOptions.defaults());
    }

    /**
     * Creates a configured harness.
     *
     * @param chatClient caller-owned provider-neutral chat client
     * @param options immutable harness options
     */
    public HarnessAgent(ChatClient chatClient, HarnessAgentOptions options) {
        Objects.requireNonNull(chatClient, "chatClient");
        HarnessAgentOptions safeOptions = Objects.requireNonNull(options, "options");
        ArrayList<AutoCloseable> owned = new ArrayList<>();
        Assembly assembly = assembleProviders(safeOptions, owned);
        contextProviders = assembly.providers();
        ChatOptions chatOptions = chatOptions(safeOptions);
        AgentMetadata metadata = new AgentMetadata(
                safeOptions.id() == null ? UUID.randomUUID().toString() : safeOptions.id(),
                safeOptions.name(),
                safeOptions.description());
        chatAgent = new ChatAgent(
                chatClient,
                metadata,
                chatOptions,
                safeOptions.tools(),
                contextProviders,
                safeOptions.agentMiddleware(),
                safeOptions.chatMiddleware(),
                safeOptions.functionMiddleware(),
                safeOptions.sessionStore());
        List<LoopEvaluator> evaluators = assembleEvaluators(safeOptions, assembly);
        if (evaluators.isEmpty()) {
            loopAgent = null;
            runtime = chatAgent;
        } else {
            loopAgent = new LoopAgent(chatAgent, evaluators, safeOptions.loopOptions(), true);
            runtime = loopAgent;
        }
        ownedResources = List.copyOf(owned);
    }

    /** Returns the assembled chat agent. */
    public ChatAgent chatAgent() {
        return chatAgent;
    }

    /** Returns the optional autonomous loop decorator. */
    public Optional<LoopAgent> loopAgent() {
        return Optional.ofNullable(loopAgent);
    }

    /** Returns context providers in execution order. */
    public List<ContextProvider> contextProviders() {
        return contextProviders;
    }

    @Override
    public AgentMetadata metadata() {
        return runtime.metadata();
    }

    @Override
    public RunHandle<AgentResponse<Void>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runtime.startRun(messages, options, cancellation);
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return runtime.runStreaming(messages, options, cancellation);
    }

    /** Creates and optionally persists a new session. */
    public CompletionStage<AgentSession> createSessionAsync() {
        return chatAgent.createSessionAsync();
    }

    /** Creates and optionally persists a new session synchronously. */
    public AgentSession createSession() {
        return chatAgent.createSession();
    }

    /**
     * Runs against one caller-owned session.
     *
     * @param session active session
     * @param messages ordered input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return completed or approval-required result
     */
    public CompletionStage<AgentRunResult<Void>> runAsync(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return loopAgent == null
                ? chatAgent.runAsync(session, messages, options, cancellation)
                : loopAgent.runAsync(session, messages, options, cancellation);
    }

    /**
     * Streams against one caller-owned session.
     *
     * @param session active session
     * @param messages ordered input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return cold update publisher
     */
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return loopAgent == null
                ? chatAgent.runStreaming(session, messages, options, cancellation)
                : loopAgent.runStreaming(session, messages, options, cancellation);
    }

    /** Returns a pending session continuation. */
    public Optional<AgentContinuation> pendingContinuation(AgentSession session) {
        return chatAgent.pendingContinuation(session);
    }

    /** Resumes a process-local or persisted approval continuation. */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentContinuation continuation, List<ToolApprovalDecision> decisions) {
        return loopAgent == null
                ? chatAgent.resumeAsync(continuation, decisions)
                : loopAgent.resumeAsync(continuation, decisions);
    }

    /** Resumes a continuation against one active session. */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentSession session, AgentContinuation continuation, List<ToolApprovalDecision> decisions) {
        return chatAgent.resumeAsync(session, continuation, decisions);
    }

    /** Discards one process-local approval continuation. */
    public boolean discardContinuation(AgentContinuation continuation) {
        return loopAgent == null
                ? chatAgent.discardContinuation(continuation)
                : loopAgent.discardContinuation(continuation);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        try {
            runtime.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        for (AutoCloseable resource : ownedResources) {
            try {
                resource.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = new IllegalStateException("Failed to close a harness-owned resource.", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Assembly assembleProviders(HarnessAgentOptions options, List<AutoCloseable> owned) {
        ArrayList<ContextProvider> providers = new ArrayList<>();
        HistoryProvider history = options.historyProvider();
        if (!options.disableCompaction()) {
            var strategy = options.compactionStrategy();
            if (strategy == null && options.maxContextWindowTokens() != null) {
                strategy = new ContextWindowCompactionStrategy(
                        options.maxContextWindowTokens(), options.maxOutputTokens());
            }
            if (strategy != null) {
                history = new CompactingHistoryProvider("harness_compaction", history, strategy);
            }
        }
        providers.add(history);

        TodoProvider todo = null;
        if (!options.disableTodo()) {
            todo = options.todoProvider() == null ? new TodoProvider() : options.todoProvider();
            providers.add(todo);
        }

        AgentModeProvider mode = null;
        if (!options.disableMode()) {
            mode = options.modeProvider() == null ? new AgentModeProvider() : options.modeProvider();
            providers.add(mode);
        }

        if (!options.disableFileMemory()) {
            FileMemoryProvider fileMemory = options.fileMemoryProvider();
            if (fileMemory == null) {
                AgentFileStore store = options.fileMemoryStore();
                if (store == null) {
                    store = new FileSystemAgentFileStore(Path.of(System.getProperty("user.dir"), "agent-file-memory"));
                    owned.add(store);
                }
                fileMemory = new FileMemoryProvider(store);
            }
            providers.add(fileMemory);
        }

        if (options.fileAccessStore() != null) {
            providers.add(new FileAccessProvider(options.fileAccessStore(), options.fileAccessOptions()));
        }
        if (options.skillsProvider() != null) {
            providers.add(options.skillsProvider());
        }
        if (!options.skillPaths().isEmpty()) {
            providers.add(SkillsProvider.fromPaths(
                    options.skillPaths(),
                    FileSkillsSourceOptions.defaults(),
                    SkillsProviderOptions.defaults(),
                    false,
                    null));
        }

        BackgroundAgentsProvider background = options.backgroundAgentsProvider();
        if (background == null && !options.backgroundAgents().isEmpty()) {
            background = new BackgroundAgentsProvider(options.backgroundAgents(), options.backgroundAgentsOptions());
            owned.add(background);
        }
        if (background != null) {
            providers.add(background);
        }
        providers.addAll(options.contextProviders());
        return new Assembly(List.copyOf(providers), todo, mode, background);
    }

    private static List<LoopEvaluator> assembleEvaluators(HarnessAgentOptions options, Assembly assembly) {
        ArrayList<LoopEvaluator> evaluators = new ArrayList<>();
        if (options.loopOnTodos()) {
            evaluators.add(new TodoCompletionLoopEvaluator(
                    Objects.requireNonNull(assembly.todo(), "todo"),
                    options.todoLoopingModes() == null ? null : assembly.mode(),
                    options.todoLoopingModes()));
        }
        if (options.loopOnBackgroundTasks()) {
            evaluators.add(new BackgroundTaskCompletionLoopEvaluator(
                    Objects.requireNonNull(assembly.background(), "background")));
        }
        evaluators.addAll(options.loopEvaluators());
        return List.copyOf(evaluators);
    }

    private static ChatOptions chatOptions(HarnessAgentOptions options) {
        ChatOptions base = options.chatOptions();
        String instructions =
                assembleInstructions(options.harnessInstructions(), base.instructions(), options.agentInstructions());
        Integer maxTokens = base.maxTokens();
        if (options.maxOutputTokens() != null) {
            maxTokens = Math.toIntExact(options.maxOutputTokens());
        }
        return new ChatOptions(
                base.model(),
                base.temperature(),
                base.topP(),
                maxTokens,
                base.stop(),
                base.seed(),
                base.frequencyPenalty(),
                base.presencePenalty(),
                base.toolChoice(),
                base.allowMultipleToolCalls(),
                base.user(),
                base.store(),
                base.conversationId(),
                instructions,
                base.structuredOutput(),
                base.metadata());
    }

    private static String assembleInstructions(String... values) {
        String instructions = java.util.Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return instructions.isEmpty() ? null : instructions;
    }

    private record Assembly(
            List<ContextProvider> providers,
            TodoProvider todo,
            AgentModeProvider mode,
            BackgroundAgentsProvider background) {}
}
