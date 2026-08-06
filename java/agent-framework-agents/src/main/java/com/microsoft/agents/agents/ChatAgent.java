// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.SynchronousExecutionException;
import com.microsoft.agents.core.ToolChoice;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.tools.FunctionContinuation;
import com.microsoft.agents.tools.FunctionInvocationLoop;
import com.microsoft.agents.tools.FunctionInvocationOptions;
import com.microsoft.agents.tools.FunctionInvocationRequest;
import com.microsoft.agents.tools.FunctionInvocationRun;
import com.microsoft.agents.tools.FunctionLoopOutcome;
import com.microsoft.agents.tools.FunctionLoopResult;
import com.microsoft.agents.tools.FunctionTools;
import com.microsoft.agents.tools.InvocationIdFactory;
import com.microsoft.agents.tools.Tool;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalDecisionRejection;
import com.microsoft.agents.tools.ToolInvocationInterceptor;
import com.microsoft.agents.tools.ToolMode;
import com.microsoft.agents.tools.ToolTurnRequest;
import com.microsoft.agents.tools.ToolTurnSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes provider-neutral chat agents with optional local function invocation.
 *
 * <p>Every run creates one {@link FunctionInvocationLoop} and passes the same explicit run identity
 * through provider requests and tool invocations. The no-tools path is one provider turn through the
 * same execution core. Tool-loop history, tool results, folded usage, and the latest finish reason
 * are mapped into the terminal {@link AgentResponse}. Streaming forwards provider and tool updates in
 * their observed order.
 *
 * <p>The chat client and caller-provided executor remain caller-owned. Closing the agent cancels and
 * awaits active logical runs, closes per-run loops, and closes only a framework-owned default
 * executor.
 */
public final class ChatAgent extends BaseAgent<Void> {
    private static final int DEFAULT_MAX_ITERATIONS = 40;

    private final ChatClient sourceChatClient;

    private final ChatClient chatClient;

    private final ChatOptions chatOptions;

    private final List<Tool> tools;

    private final List<ContextProvider> contextProviders;

    private final List<FunctionMiddleware> functionMiddleware;

    private final SessionStore sessionStore;

    private final PendingAgentRunStateCodec pendingStateCodec = new PendingAgentRunStateCodec();

    private final Set<FunctionInvocationLoop> activeLoops = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, SuspendedExecution> suspendedExecutions = new ConcurrentHashMap<>();

    /**
     * Creates an unnamed no-tools agent with a framework-owned virtual-thread executor.
     *
     * @param chatClient provider-neutral chat client
     */
    public ChatAgent(ChatClient chatClient) {
        this(
                chatClient,
                AgentMetadata.create(),
                ChatOptions.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    /**
     * Creates an unnamed agent with tools and a framework-owned virtual-thread executor.
     *
     * @param chatClient provider-neutral chat client
     * @param tools local tools
     */
    public ChatAgent(ChatClient chatClient, Collection<? extends Tool> tools) {
        this(
                chatClient,
                AgentMetadata.create(),
                ChatOptions.empty(),
                tools,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    /**
     * Creates a configured agent with a framework-owned virtual-thread executor.
     *
     * @param chatClient provider-neutral chat client
     * @param metadata immutable agent metadata
     * @param chatOptions default chat options
     * @param tools local tools
     */
    public ChatAgent(
            ChatClient chatClient, AgentMetadata metadata, ChatOptions chatOptions, Collection<? extends Tool> tools) {
        this(chatClient, metadata, chatOptions, tools, List.of(), List.of(), List.of(), List.of(), null);
    }

    /**
     * Creates a configured session, provider, and middleware-aware agent with an owned executor.
     *
     * @param chatClient caller-owned provider-neutral chat client
     * @param metadata immutable agent metadata
     * @param chatOptions default chat options
     * @param tools configured local tools
     * @param contextProviders providers in registration order
     * @param agentMiddleware agent middleware in registration order
     * @param chatMiddleware chat middleware in registration order
     * @param functionMiddleware function middleware in registration order
     * @param sessionStore optional optimistic session store
     */
    public ChatAgent(
            ChatClient chatClient,
            AgentMetadata metadata,
            ChatOptions chatOptions,
            Collection<? extends Tool> tools,
            Collection<? extends ContextProvider> contextProviders,
            Collection<? extends AgentMiddleware<Void>> agentMiddleware,
            Collection<? extends ChatMiddleware> chatMiddleware,
            Collection<? extends FunctionMiddleware> functionMiddleware,
            SessionStore sessionStore) {
        super(metadata, agentMiddleware);
        this.sourceChatClient = AgentValidation.requireNonNull(chatClient, "chatClient");
        this.chatClient = chatMiddleware.isEmpty()
                ? sourceChatClient
                : new MiddlewareChatClient(sourceChatClient, chatMiddleware);
        this.chatOptions = AgentValidation.requireNonNull(chatOptions, "chatOptions");
        this.tools = normalizeTools(tools);
        this.contextProviders = normalizeProviders(contextProviders);
        this.functionMiddleware = List.copyOf(AgentValidation.requireNonNull(functionMiddleware, "functionMiddleware"));
        if (this.functionMiddleware.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("functionMiddleware contains null");
        }
        this.sessionStore = sessionStore;
        validateToolChoice(this.tools, this.chatOptions, this.contextProviders);
    }

    /**
     * Creates a configured agent using a caller-owned executor.
     *
     * @param chatClient provider-neutral chat client
     * @param metadata immutable agent metadata
     * @param chatOptions default chat options
     * @param tools local tools
     * @param executor caller-owned executor, which this agent never closes
     */
    public ChatAgent(
            ChatClient chatClient,
            AgentMetadata metadata,
            ChatOptions chatOptions,
            Collection<? extends Tool> tools,
            Executor executor) {
        this(chatClient, metadata, chatOptions, tools, List.of(), List.of(), List.of(), List.of(), null, executor);
    }

    /**
     * Creates a fully configured agent using a caller-owned executor.
     *
     * @param chatClient caller-owned chat client
     * @param metadata immutable agent metadata
     * @param chatOptions default chat options
     * @param tools configured tools
     * @param contextProviders providers in registration order
     * @param agentMiddleware agent middleware in registration order
     * @param chatMiddleware chat middleware in registration order
     * @param functionMiddleware function middleware in registration order
     * @param sessionStore optional session store
     * @param executor caller-owned executor
     */
    public ChatAgent(
            ChatClient chatClient,
            AgentMetadata metadata,
            ChatOptions chatOptions,
            Collection<? extends Tool> tools,
            Collection<? extends ContextProvider> contextProviders,
            Collection<? extends AgentMiddleware<Void>> agentMiddleware,
            Collection<? extends ChatMiddleware> chatMiddleware,
            Collection<? extends FunctionMiddleware> functionMiddleware,
            SessionStore sessionStore,
            Executor executor) {
        super(metadata, executor, agentMiddleware);
        this.sourceChatClient = AgentValidation.requireNonNull(chatClient, "chatClient");
        this.chatClient = chatMiddleware.isEmpty()
                ? sourceChatClient
                : new MiddlewareChatClient(sourceChatClient, chatMiddleware);
        this.chatOptions = AgentValidation.requireNonNull(chatOptions, "chatOptions");
        this.tools = normalizeTools(tools);
        this.contextProviders = normalizeProviders(contextProviders);
        this.functionMiddleware = List.copyOf(AgentValidation.requireNonNull(functionMiddleware, "functionMiddleware"));
        if (this.functionMiddleware.stream().anyMatch(java.util.Objects::isNull)) {
            throw new NullPointerException("functionMiddleware contains null");
        }
        this.sessionStore = sessionStore;
        validateToolChoice(this.tools, this.chatOptions, this.contextProviders);
    }

    /**
     * Returns the caller-owned chat client.
     *
     * @return chat client
     */
    public ChatClient chatClient() {
        return sourceChatClient;
    }

    /**
     * Returns immutable default chat options.
     *
     * @return chat options
     */
    public ChatOptions chatOptions() {
        return chatOptions;
    }

    /**
     * Returns immutable normalized local tools.
     *
     * @return local tools
     */
    public List<Tool> tools() {
        return tools;
    }

    /**
     * Returns the optional configured session store.
     *
     * @return configured store
     */
    public Optional<SessionStore> sessionStore() {
        return Optional.ofNullable(sessionStore);
    }

    /**
     * Creates and, when configured, persists a new session.
     *
     * @return new session stage
     */
    public CompletionStage<AgentSession> createSessionAsync() {
        AgentSession session = new AgentSession();
        return persistSessionAsync(session).thenApply(ignored -> session);
    }

    /**
     * Creates and, when configured, persists a new session synchronously.
     *
     * @return new session
     */
    public AgentSession createSession() {
        return await(createSessionAsync(), "Session creation");
    }

    /**
     * Loads a detached mutable session runtime from the configured store.
     *
     * @param key session key
     * @return optional restored session
     */
    public CompletionStage<Optional<AgentSession>> loadSessionAsync(SessionKey key) {
        SessionStore store = requireSessionStore();
        return store.loadAsync(AgentValidation.requireNonNull(key, "key"))
                .thenApply(loaded ->
                        loaded.map(versioned -> AgentSession.restore(versioned.snapshot(), versioned.revision())));
    }

    /**
     * Loads a session by identity.
     *
     * @param sessionId session identifier
     * @return optional restored session
     */
    public CompletionStage<Optional<AgentSession>> loadSessionAsync(String sessionId) {
        return loadSessionAsync(new SessionKey(sessionId));
    }

    /**
     * Saves a session using its current optimistic revision.
     *
     * @param session session runtime
     * @return completion stage
     */
    public CompletionStage<Void> saveSessionAsync(AgentSession session) {
        requireSessionStore();
        return persistSessionAsync(AgentValidation.requireNonNull(session, "session"));
    }

    /**
     * Deletes a session using its current optimistic revision.
     *
     * @param session session runtime
     * @return completion stage
     */
    public CompletionStage<Void> deleteSessionAsync(AgentSession session) {
        SessionStore store = requireSessionStore();
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        return store.deleteAsync(SessionKey.of(safeSession), safeSession.revision());
    }

    /**
     * Runs text against one mutable session.
     *
     * @param session active session
     * @param input user input
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> runAsync(AgentSession session, String input) {
        return runAsync(
                session,
                List.of(Message.text(
                        com.microsoft.agents.core.Role.USER, AgentValidation.requireNonBlank(input, "input"))),
                RunOptions.empty(),
                new DefaultRunCancellation());
    }

    /**
     * Runs ordered messages against one mutable session.
     *
     * @param session active session
     * @param messages ordered caller input
     * @param options run options
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> runAsync(
            AgentSession session, List<Message> messages, RunOptions options) {
        return runAsync(session, messages, options, new DefaultRunCancellation());
    }

    /**
     * Runs ordered messages against one mutable session with explicit cancellation.
     *
     * @param session active session
     * @param messages ordered caller input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> runAsync(
            AgentSession session, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        safeSession.beginRun();
        if (safeSession.pendingRun() != null) {
            safeSession.endRun();
            return CompletableFuture.failedFuture(
                    new SessionBusyException("Session '" + safeSession.sessionId() + "' has a pending continuation."));
        }
        CompletionStage<AgentResponse<Void>> responseStage;
        try {
            responseStage = startRunWithSession(messages, options, cancellation, safeSession)
                    .resultAsync();
        } catch (RuntimeException failure) {
            safeSession.endRun();
            return CompletableFuture.failedFuture(failure);
        }
        return toRunResult(responseStage).whenComplete((ignored, failure) -> safeSession.endRun());
    }

    /**
     * Runs text synchronously against one mutable session.
     *
     * @param session active session
     * @param input user input
     * @return completed or input-required result
     */
    public AgentRunResult<Void> run(AgentSession session, String input) {
        return await(runAsync(session, input), "Session agent run");
    }

    /**
     * Streams one session-aware run.
     *
     * <p>An approval suspension terminates the publisher with {@link ApprovalRequiredException} after
     * safe pending state has been saved. Call {@link #pendingContinuation(AgentSession)} and a resume
     * method to continue.
     *
     * @param session active session
     * @param messages ordered caller input
     * @param options run options
     * @return cold single-subscriber publisher
     */
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            AgentSession session, List<Message> messages, RunOptions options) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        List<Message> safeMessages = AgentValidation.copyMessages(messages);
        RunOptions safeOptions = AgentValidation.requireNonNull(options, "options");
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicBoolean gateHeld = new AtomicBoolean();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> {
                    try {
                        safeSession.beginRun();
                        gateHeld.set(true);
                        if (safeSession.pendingRun() != null) {
                            throw new SessionBusyException(
                                    "Session '" + safeSession.sessionId() + "' has a pending continuation.");
                        }
                        runStreamingWithSession(safeMessages, safeOptions, cancellation, safeSession)
                                .subscribe(forwardingSubscriber(
                                        sinkReference.get(), upstream, () -> releaseGate(safeSession, gateHeld)));
                    } catch (RuntimeException failure) {
                        releaseGate(safeSession, gateHeld);
                        sinkReference.get().fail(failure);
                    }
                },
                () -> {
                    cancellation.cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    releaseGate(safeSession, gateHeld);
                },
                FunctionInvocationOptions.DEFAULT_MAX_BUFFERED_UPDATES);
        sinkReference.set(sink);
        return sink;
    }

    /**
     * Returns the pending continuation currently stored in a session.
     *
     * @param session session runtime
     * @return optional pending continuation
     */
    public Optional<AgentContinuation> pendingContinuation(AgentSession session) {
        StateValue.ObjectValue pending =
                AgentValidation.requireNonNull(session, "session").pendingRun();
        return pending == null
                ? Optional.empty()
                : Optional.of(pendingStateCodec.decode(pending).continuation());
    }

    /**
     * Resumes a persisted session continuation.
     *
     * @param session owning session
     * @param continuation one-time continuation descriptor
     * @param decisions approval decisions
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentSession session, AgentContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        return resumeAsync(session, continuation, decisions, new DefaultRunCancellation());
    }

    /**
     * Resumes a persisted session continuation with caller-owned cancellation.
     *
     * @param session owning session
     * @param continuation one-time continuation descriptor
     * @param decisions approval decisions
     * @param cancellation caller-owned cancellation
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentSession session,
            AgentContinuation continuation,
            Collection<ToolApprovalDecision> decisions,
            RunCancellation cancellation) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        AgentContinuation safeContinuation = AgentValidation.requireNonNull(continuation, "continuation");
        List<ToolApprovalDecision> safeDecisions = List.copyOf(AgentValidation.requireNonNull(decisions, "decisions"));
        RunCancellation safeCancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        safeSession.beginRun();
        PendingAgentRunState pending;
        try {
            pending = requirePending(safeSession, safeContinuation);
        } catch (RuntimeException failure) {
            safeSession.endRun();
            return CompletableFuture.failedFuture(failure);
        }
        StateValue.ObjectValue encodedPending = safeSession.pendingRun();
        safeSession.pendingRun(null);
        CompletionStage<AgentRunResult<Void>> resumed = persistSessionAsync(safeSession)
                .handle((ignored, consumeFailure) -> {
                    if (consumeFailure != null) {
                        safeSession.pendingRun(encodedPending);
                        throw new java.util.concurrent.CompletionException(RunHandles.unwrap(consumeFailure));
                    }
                    return ignored;
                })
                .thenCompose(ignored -> resumePersistedAsync(safeSession, pending, safeDecisions, safeCancellation));
        return resumed.whenComplete((ignored, failure) -> safeSession.endRun());
    }

    /**
     * Resumes an explicit process-local continuation.
     *
     * @param continuation process-local continuation
     * @param decisions approval decisions
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        return resumeAsync(continuation, decisions, new DefaultRunCancellation());
    }

    /**
     * Resumes an explicit process-local continuation with caller-owned cancellation.
     *
     * @param continuation process-local continuation
     * @param decisions approval decisions
     * @param cancellation caller-owned cancellation
     * @return completed or input-required result
     */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentContinuation continuation, Collection<ToolApprovalDecision> decisions, RunCancellation cancellation) {
        AgentContinuation safeContinuation = AgentValidation.requireNonNull(continuation, "continuation");
        List<ToolApprovalDecision> safeDecisions = List.copyOf(AgentValidation.requireNonNull(decisions, "decisions"));
        RunCancellation safeCancellation = AgentValidation.requireNonNull(cancellation, "cancellation");
        if (safeContinuation.sessionId() != null) {
            return CompletableFuture.failedFuture(
                    new MiddlewareException("A session-bound continuation must be resumed with its AgentSession."));
        }
        SuspendedExecution suspended = suspendedExecutions.remove(safeContinuation.continuationId());
        if (suspended == null || !suspended.suspended().logicalRunId().equals(safeContinuation.logicalRunId())) {
            return CompletableFuture.failedFuture(new MiddlewareException(
                    "The process-local continuation is stale, consumed, or belongs to another agent."));
        }
        FunctionInvocationRun run;
        try {
            run = suspended.execution().loop().resume(suspended.suspended(), safeDecisions);
        } catch (RuntimeException failure) {
            cleanup(suspended.execution(), suspended.suspended().logicalRunId());
            return CompletableFuture.failedFuture(failure);
        }
        RunCancellationRegistration cancellationRegistration = RunCancellations.register(safeCancellation, run::cancel);
        LoopExecution resumed = new LoopExecution(
                suspended.execution().loop(),
                null,
                run.resultAsync(),
                suspended.execution().initialMessageCount());
        return mapResult(resumed, suspended.prepared())
                .handle((response, failure) -> {
                    if (failure == null) {
                        return AgentRunResult.completed(
                                response,
                                run.resultAsync().toCompletableFuture().join().rejectedDecisions());
                    }
                    Throwable cause = RunHandles.unwrap(failure);
                    if (cause instanceof ApprovalRequiredException approval) {
                        return AgentRunResult.<Void>inputRequired(
                                approval.continuation(), approval.rejectedDecisions());
                    }
                    throw new java.util.concurrent.CompletionException(cause);
                })
                .whenComplete((ignored, failure) -> cancellationRegistration.close());
    }

    /**
     * Abandons one explicit process-local continuation and releases its retained loop.
     *
     * <p>Session-bound pending state is owned by its {@link AgentSession} and is not affected.
     *
     * @param continuation process-local continuation
     * @return {@code true} when retained state was removed
     */
    public boolean discardContinuation(AgentContinuation continuation) {
        AgentContinuation checked = AgentValidation.requireNonNull(continuation, "continuation");
        if (checked.sessionId() != null) {
            return false;
        }
        SuspendedExecution suspended = suspendedExecutions.get(checked.continuationId());
        if (suspended == null
                || !suspended.suspended().logicalRunId().equals(checked.logicalRunId())
                || !suspendedExecutions.remove(checked.continuationId(), suspended)) {
            return false;
        }
        cleanup(suspended.execution(), suspended.suspended().logicalRunId());
        return true;
    }

    /**
     * Resumes a session continuation synchronously.
     *
     * @param session owning session
     * @param continuation continuation descriptor
     * @param decisions approval decisions
     * @return completed or input-required result
     */
    public AgentRunResult<Void> resume(
            AgentSession session, AgentContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        return await(resumeAsync(session, continuation, decisions), "Session agent resume");
    }

    /**
     * Streams a persisted session continuation.
     *
     * @param session owning session
     * @param continuation continuation descriptor
     * @param decisions approval decisions
     * @return cold single-subscriber update publisher
     */
    public Flow.Publisher<AgentResponseUpdate> resumeStreaming(
            AgentSession session, AgentContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        AgentSession safeSession = AgentValidation.requireNonNull(session, "session");
        AgentContinuation safeContinuation = AgentValidation.requireNonNull(continuation, "continuation");
        List<ToolApprovalDecision> safeDecisions = List.copyOf(decisions);
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        AtomicBoolean gateHeld = new AtomicBoolean();
        AtomicBoolean updatesDone = new AtomicBoolean();
        AtomicBoolean resultDone = new AtomicBoolean();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> {
                    PendingAgentRunState pending;
                    StateValue.ObjectValue encoded;
                    try {
                        safeSession.beginRun();
                        gateHeld.set(true);
                        pending = requirePending(safeSession, safeContinuation);
                        encoded = safeSession.pendingRun();
                        safeSession.pendingRun(null);
                    } catch (RuntimeException failure) {
                        releaseGate(safeSession, gateHeld);
                        sinkReference.get().fail(failure);
                        return;
                    }
                    persistSessionAsync(safeSession)
                            .handle((ignored, failure) -> {
                                if (failure != null) {
                                    safeSession.pendingRun(encoded);
                                    throw new java.util.concurrent.CompletionException(RunHandles.unwrap(failure));
                                }
                                return ignored;
                            })
                            .thenCompose(ignored ->
                                    preparePersistedResume(safeSession, pending, safeDecisions, cancellation, true))
                            .whenComplete((resume, failure) -> {
                                if (failure != null) {
                                    releaseGate(safeSession, gateHeld);
                                    sinkReference.get().fail(RunHandles.unwrap(failure));
                                    return;
                                }
                                resume.execution().streamingRun().updates().subscribe(new Flow.Subscriber<>() {
                                    @Override
                                    public void onSubscribe(Flow.Subscription subscription) {
                                        upstream.set(subscription);
                                        subscription.request(Long.MAX_VALUE);
                                    }

                                    @Override
                                    public void onNext(ChatResponseUpdate update) {
                                        sinkReference
                                                .get()
                                                .emit(toAgentUpdate(
                                                        update,
                                                        resume.prepared().context()));
                                    }

                                    @Override
                                    public void onError(Throwable streamFailure) {
                                        releaseGate(safeSession, gateHeld);
                                        sinkReference.get().fail(streamFailure);
                                    }

                                    @Override
                                    public void onComplete() {
                                        updatesDone.set(true);
                                        if (resultDone.get()) {
                                            releaseGate(safeSession, gateHeld);
                                            sinkReference.get().complete();
                                        }
                                    }
                                });
                                mapResult(resume.execution(), resume.prepared())
                                        .whenComplete((response, resultFailure) -> {
                                            if (resultFailure != null) {
                                                releaseGate(safeSession, gateHeld);
                                                sinkReference.get().fail(RunHandles.unwrap(resultFailure));
                                                return;
                                            }
                                            resultDone.set(true);
                                            if (updatesDone.get()) {
                                                releaseGate(safeSession, gateHeld);
                                                sinkReference.get().complete();
                                            }
                                        });
                            });
                },
                () -> {
                    cancellation.cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    releaseGate(safeSession, gateHeld);
                },
                FunctionInvocationOptions.DEFAULT_MAX_BUFFERED_UPDATES);
        sinkReference.set(sink);
        return sink;
    }

    private CompletionStage<AgentRunResult<Void>> toRunResult(CompletionStage<AgentResponse<Void>> responseStage) {
        return responseStage.handle((response, failure) -> {
            if (failure == null) {
                return AgentRunResult.completed(response, List.of());
            }
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof ApprovalRequiredException approval) {
                return AgentRunResult.<Void>inputRequired(approval.continuation(), approval.rejectedDecisions());
            }
            throw new java.util.concurrent.CompletionException(cause);
        });
    }

    private PendingAgentRunState requirePending(AgentSession session, AgentContinuation continuation) {
        StateValue.ObjectValue encoded = session.pendingRun();
        if (encoded == null) {
            throw new MiddlewareException("The continuation is stale, already consumed, or not pending.");
        }
        PendingAgentRunState pending = pendingStateCodec.decode(encoded);
        AgentContinuation stored = pending.continuation();
        if (!session.sessionId().equals(continuation.sessionId())
                || !session.sessionId().equals(stored.sessionId())
                || !stored.continuationId().equals(continuation.continuationId())
                || !stored.logicalRunId().equals(continuation.logicalRunId())) {
            throw new MiddlewareException("The continuation belongs to a different session or logical run.");
        }
        return pending;
    }

    private CompletionStage<AgentRunResult<Void>> resumePersistedAsync(
            AgentSession session,
            PendingAgentRunState pending,
            List<ToolApprovalDecision> decisions,
            RunCancellation cancellation) {
        return preparePersistedResume(session, pending, decisions, cancellation, false)
                .thenCompose(resume -> {
                    CompletionStage<FunctionLoopResult> loopResult =
                            resume.execution().resultAsync();
                    return mapResult(resume.execution(), resume.prepared()).handle((response, failure) -> {
                        if (failure == null) {
                            List<ToolApprovalDecisionRejection> rejections =
                                    loopResult.toCompletableFuture().join().rejectedDecisions();
                            return AgentRunResult.completed(response, rejections);
                        }
                        Throwable cause = RunHandles.unwrap(failure);
                        if (cause instanceof ApprovalRequiredException approval) {
                            return AgentRunResult.<Void>inputRequired(
                                    approval.continuation(), approval.rejectedDecisions());
                        }
                        throw new java.util.concurrent.CompletionException(cause);
                    });
                });
    }

    private CompletionStage<PreparedResume> preparePersistedResume(
            AgentSession session,
            PendingAgentRunState pending,
            List<ToolApprovalDecision> decisions,
            RunCancellation cancellation,
            boolean streaming) {
        SuspendedExecution local =
                suspendedExecutions.remove(pending.continuation().continuationId());
        if (local != null) {
            cleanup(local.execution(), local.suspended().logicalRunId());
        }
        AgentRunContext context = new AgentRunContext(
                pending.continuation().logicalRunId(),
                metadata(),
                Instant.now(),
                pending.inputMessages(),
                pending.options(),
                cancellation,
                pending.options().metadata(),
                session,
                ContextContribution.empty());
        return prepareRunAsync(context).thenApply(prepared -> {
            LoopExecution execution;
            try {
                execution = startRestoredLoop(prepared, pending, decisions, cancellation, streaming);
            } catch (RuntimeException failure) {
                throw failure;
            }
            return new PreparedResume(execution, prepared);
        });
    }

    private LoopExecution startRestoredLoop(
            PreparedRun prepared,
            PendingAgentRunState pending,
            List<ToolApprovalDecision> decisions,
            RunCancellation cancellation,
            boolean streaming) {
        AgentRunContext context = prepared.context();
        List<ToolInvocationInterceptor> interceptors = functionMiddleware.isEmpty()
                ? List.of()
                : List.of(new FunctionMiddlewareInterceptor(context.session(), functionMiddleware));
        FunctionInvocationLoop loop = new FunctionInvocationLoop(
                new ClientTurnSource(context),
                prepared.tools(),
                executor(),
                InvocationIdFactory.defaultFactory(),
                null,
                interceptors);
        activeLoops.add(loop);
        try {
            FunctionInvocationRun run = streaming
                    ? loop.resumeStreaming(pending.functionContinuation(), decisions, cancellation)
                    : loop.resume(pending.functionContinuation(), decisions, cancellation);
            return streaming
                    ? new LoopExecution(loop, run, null, pending.initialMessageCount())
                    : new LoopExecution(loop, null, run.resultAsync(), pending.initialMessageCount());
        } catch (RuntimeException failure) {
            activeLoops.remove(loop);
            loop.close();
            throw failure;
        }
    }

    private SessionStore requireSessionStore() {
        if (sessionStore == null) {
            throw new com.microsoft.agents.core.ValidationException("This ChatAgent has no SessionStore configured.");
        }
        return sessionStore;
    }

    private static <T> T await(CompletionStage<T> stage, String operation) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SynchronousExecutionException(operation + " was interrupted.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = RunHandles.unwrap(exception.getCause());
            if (cause instanceof RunCancelledException cancelled) {
                throw cancelled;
            }
            throw new SynchronousExecutionException(operation + " failed.", cause);
        }
    }

    private static Flow.Subscriber<AgentResponseUpdate> forwardingSubscriber(
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            AtomicReference<Flow.Subscription> upstream,
            Runnable terminal) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                upstream.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentResponseUpdate item) {
                sink.emit(item);
            }

            @Override
            public void onError(Throwable failure) {
                terminal.run();
                sink.fail(failure);
            }

            @Override
            public void onComplete() {
                terminal.run();
                sink.complete();
            }
        };
    }

    private static void releaseGate(AgentSession session, AtomicBoolean gateHeld) {
        if (gateHeld.compareAndSet(true, false)) {
            session.endRun();
        }
    }

    @Override
    protected CompletionStage<AgentResponse<Void>> executeAsync(AgentRunContext context) {
        return prepareRunAsync(context).thenCompose(prepared -> {
            LoopExecution execution = startLoop(prepared, false);
            return mapResult(execution, prepared);
        });
    }

    @Override
    protected StreamingExecution<Void> executeStreaming(AgentRunContext context) {
        CompletableFuture<AgentResponse<Void>> result = new CompletableFuture<>();
        AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
        SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                () -> prepareRunAsync(context).whenComplete((prepared, preparationFailure) -> {
                    if (preparationFailure != null) {
                        Throwable cause = RunHandles.unwrap(preparationFailure);
                        result.completeExceptionally(cause);
                        sinkReference.get().fail(cause);
                        return;
                    }
                    LoopExecution execution;
                    try {
                        execution = startLoop(prepared, true);
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                        sinkReference.get().fail(failure);
                        return;
                    }
                    execution.streamingRun().updates().subscribe(new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            if (!upstream.compareAndSet(null, subscription)) {
                                subscription.cancel();
                            } else {
                                subscription.request(Long.MAX_VALUE);
                            }
                        }

                        @Override
                        public void onNext(ChatResponseUpdate update) {
                            sinkReference.get().emit(toAgentUpdate(update, prepared.context()));
                        }

                        @Override
                        public void onError(Throwable failure) {
                            sinkReference.get().fail(failure);
                        }

                        @Override
                        public void onComplete() {
                            sinkReference.get().complete();
                        }
                    });
                    mapResult(execution, prepared).whenComplete((response, failure) -> {
                        if (failure == null) {
                            result.complete(response);
                        } else {
                            result.completeExceptionally(RunHandles.unwrap(failure));
                        }
                    });
                }),
                () -> {
                    context.cancellation().cancel();
                    Flow.Subscription subscription = upstream.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                },
                FunctionInvocationOptions.DEFAULT_MAX_BUFFERED_UPDATES);
        sinkReference.set(sink);
        return new StreamingExecution<>(sink, result.minimalCompletionStage());
    }

    @Override
    protected void closeResources() {
        activeLoops.forEach(FunctionInvocationLoop::close);
        activeLoops.clear();
        suspendedExecutions.clear();
    }

    int activeLoopCountForDiagnostics() {
        return activeLoops.size();
    }

    int suspendedExecutionCountForDiagnostics() {
        return suspendedExecutions.size();
    }

    private LoopExecution startLoop(PreparedRun prepared, boolean streaming) {
        AgentRunContext context = prepared.context();
        ToolMode initialMode = initialToolMode(prepared.tools());
        FunctionInvocationOptions invocationOptions = new FunctionInvocationOptions(
                context.options().maxIterations() == null
                        ? DEFAULT_MAX_ITERATIONS
                        : context.options().maxIterations(),
                context.options().maxFunctionCalls(),
                initialMode,
                false,
                FunctionInvocationOptions.DEFAULT_MAX_BUFFERED_UPDATES);
        FunctionInvocationRequest request = new FunctionInvocationRequest(
                context.runId(),
                context.inputMessages(),
                invocationOptions,
                context.cancellation(),
                context.metadata());
        List<ToolInvocationInterceptor> interceptors = functionMiddleware.isEmpty()
                ? List.of()
                : List.of(new FunctionMiddlewareInterceptor(context.session(), functionMiddleware));
        FunctionInvocationLoop loop = new FunctionInvocationLoop(
                new ClientTurnSource(context),
                prepared.tools(),
                executor(),
                InvocationIdFactory.defaultFactory(),
                null,
                interceptors);
        activeLoops.add(loop);
        try {
            if (streaming) {
                return new LoopExecution(
                        loop,
                        loop.startStreaming(request),
                        null,
                        context.inputMessages().size());
            }
            return new LoopExecution(
                    loop, null, loop.runAsync(request), context.inputMessages().size());
        } catch (RuntimeException failure) {
            activeLoops.remove(loop);
            loop.close();
            throw failure;
        }
    }

    private CompletionStage<AgentResponse<Void>> mapResult(LoopExecution execution, PreparedRun prepared) {
        CompletableFuture<AgentResponse<Void>> mapped = new CompletableFuture<>();
        execution.resultAsync().whenComplete((result, failure) -> {
            CompletionStage<AgentResponse<Void>> processing;
            if (failure != null) {
                Throwable cause = RunHandles.unwrap(failure);
                processing = completeProviders(prepared, null, cause).handle((ignored, providerFailure) -> {
                    if (providerFailure != null) {
                        cause.addSuppressed(RunHandles.unwrap(providerFailure));
                    }
                    throw new java.util.concurrent.CompletionException(cause);
                });
            } else {
                try {
                    processing = processLoopResult(
                            execution, prepared, AgentValidation.requireNonNull(result, "function loop result"));
                } catch (RuntimeException mappingFailure) {
                    processing = CompletableFuture.failedFuture(mappingFailure);
                }
            }
            processing.whenComplete((response, processingFailure) -> {
                if (processingFailure == null) {
                    mapped.complete(response);
                } else {
                    Throwable cause = RunHandles.unwrap(processingFailure);
                    if (!(cause instanceof ApprovalRequiredException)) {
                        cleanup(execution, prepared.context().runId());
                    }
                    mapped.completeExceptionally(cause);
                }
            });
        });
        return mapped.minimalCompletionStage();
    }

    private CompletionStage<AgentResponse<Void>> processLoopResult(
            LoopExecution execution, PreparedRun prepared, FunctionLoopResult result) {
        if (result.outcome() == FunctionLoopOutcome.INPUT_REQUIRED) {
            return suspend(execution, prepared, result);
        }
        AgentResponse<Void> response = toAgentResponse(result, execution.initialMessageCount(), prepared.context());
        return completeProviders(prepared, response, null)
                .thenCompose(ignored -> {
                    AgentSession session = prepared.context().session();
                    if (session != null) {
                        session.pendingRun(null);
                    }
                    return persistSessionAsync(session);
                })
                .thenApply(ignored -> response)
                .whenComplete((ignored, failure) ->
                        cleanup(execution, prepared.context().runId()));
    }

    private static AgentResponse<Void> toAgentResponse(
            FunctionLoopResult result, int initialMessageCount, AgentRunContext context) {
        List<Message> history = result.history();
        if (history.size() < initialMessageCount) {
            throw new AgentExecutionException("Function loop returned history shorter than its initial input.");
        }
        ChatResponse latest = result.latestResponse();
        if (latest == null) {
            throw new AgentExecutionException("Function loop completed without a provider response.");
        }
        List<Message> outputMessages = List.copyOf(history.subList(initialMessageCount, history.size()));
        return new AgentResponse<>(
                outputMessages,
                latest.responseId(),
                context.agent().id(),
                latest.createdAt(),
                latest.finishReason(),
                result.usage(),
                null,
                latest.continuationToken(),
                latest.metadata(),
                latest.updateSequences());
    }

    private CompletionStage<AgentResponse<Void>> suspend(
            LoopExecution execution, PreparedRun prepared, FunctionLoopResult result) {
        FunctionContinuation functionContinuation = result.continuation();
        String continuationId = UUID.randomUUID().toString();
        AgentSession session = prepared.context().session();
        AgentContinuation continuation = new AgentContinuation(
                continuationId,
                session == null ? null : session.sessionId(),
                result.logicalRunId(),
                result.approvalRequests(),
                session != null && sessionStore != null,
                false);
        PendingAgentRunState pending = new PendingAgentRunState(
                continuation,
                functionContinuation,
                prepared.originalInput(),
                prepared.context().options(),
                execution.initialMessageCount());
        if (session == null) {
            suspendedExecutions.put(continuationId, new SuspendedExecution(execution, result, prepared));
            return CompletableFuture.failedFuture(
                    new ApprovalRequiredException(continuation, result.rejectedDecisions()));
        }

        StateValue.ObjectValue priorPending = session.pendingRun();
        session.pendingRun(pendingStateCodec.encode(pending));
        return persistSessionAsync(session)
                .handle((ignored, failure) -> failure)
                .thenCompose(failure -> {
                    if (failure != null) {
                        session.pendingRun(priorPending);
                        return CompletableFuture.failedFuture(RunHandles.unwrap(failure));
                    }
                    cleanup(execution, result.logicalRunId());
                    return CompletableFuture.failedFuture(
                            new ApprovalRequiredException(continuation, result.rejectedDecisions()));
                });
    }

    private void cleanup(LoopExecution execution, String logicalRunId) {
        execution.loop().release(logicalRunId);
        activeLoops.remove(execution.loop());
        execution.loop().close();
    }

    private CompletionStage<PreparedRun> prepareRunAsync(AgentRunContext context) {
        AgentSession providerSession =
                context.session() == null ? new AgentSession("process-local-" + context.runId()) : context.session();
        PreparationAccumulator accumulator = new PreparationAccumulator(context, providerSession, tools);
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (ContextProvider provider : contextProviders) {
            stage = stage.thenCompose(ignored -> {
                if (context.cancellation().isCancellationRequested()) {
                    return CompletableFuture.failedFuture(new com.microsoft.agents.core.RunCancelledException());
                }
                ContextProviderRequest request = accumulator.request();
                accumulator.requests().add(request);
                CompletionStage<ContextContribution> provided;
                try {
                    provided = provider.provideAsync(request);
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
                if (provided == null) {
                    return CompletableFuture.failedFuture(new AgentExecutionException(
                            "Context provider '" + provider.id() + "' returned a null stage."));
                }
                return provided.thenAccept(contribution ->
                        accumulator.append(AgentValidation.requireNonNull(contribution, "context contribution")));
            });
        }
        return stage.thenApply(ignored -> accumulator.finish());
    }

    private CompletionStage<Void> completeProviders(
            PreparedRun prepared, AgentResponse<Void> response, Throwable failure) {
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (int index = 0; index < contextProviders.size(); index++) {
            ContextProvider provider = contextProviders.get(index);
            ContextProviderRequest request = prepared.providerRequests().get(index);
            stage = stage.thenCompose(ignored -> {
                ContextProviderCompletion completion =
                        new ContextProviderCompletion(request, prepared.originalInput(), response, failure);
                CompletionStage<Void> completed;
                try {
                    completed = provider.completedAsync(completion);
                } catch (RuntimeException providerFailure) {
                    return CompletableFuture.failedFuture(providerFailure);
                }
                return completed == null
                        ? CompletableFuture.failedFuture(new AgentExecutionException(
                                "Context provider '" + provider.id() + "' returned a null completion stage."))
                        : completed;
            });
        }
        return stage;
    }

    private CompletionStage<Void> persistSessionAsync(AgentSession session) {
        if (session == null || sessionStore == null) {
            return CompletableFuture.completedFuture(null);
        }
        long expectedRevision = session.revision();
        return sessionStore
                .saveAsync(SessionKey.of(session), session.snapshot(), expectedRevision)
                .thenAccept(stored -> session.persisted(stored.revision()));
    }

    private static Flow.Subscriber<ChatResponseUpdate> mappingSubscriber(
            Flow.Subscriber<? super AgentResponseUpdate> subscriber, AgentRunContext context) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(ChatResponseUpdate update) {
                subscriber.onNext(toAgentUpdate(update, context));
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        };
    }

    private static AgentResponseUpdate toAgentUpdate(ChatResponseUpdate update, AgentRunContext context) {
        String authorName = update.authorName() == null ? context.agent().name() : update.authorName();
        return new AgentResponseUpdate(
                update.sequence(),
                update.contents(),
                update.role(),
                authorName,
                context.agent().id(),
                update.responseId(),
                update.messageId(),
                update.createdAt(),
                update.finishReason(),
                update.usage(),
                update.continuationToken(),
                update.metadata());
    }

    private ToolMode initialToolMode(List<Tool> effectiveTools) {
        if (effectiveTools.isEmpty()) {
            return ToolMode.NONE;
        }
        ToolChoice choice = chatOptions.toolChoice();
        if (choice == null || choice == ToolChoice.AUTO) {
            return ToolMode.AUTO;
        }
        if (choice == ToolChoice.REQUIRED) {
            return ToolMode.REQUIRED;
        }
        return ToolMode.NONE;
    }

    private ChatOptions optionsFor(ToolMode mode, AgentRunContext context) {
        ToolChoice choice =
                switch (mode) {
                    case AUTO -> ToolChoice.AUTO;
                    case REQUIRED -> ToolChoice.REQUIRED;
                    case NONE -> ToolChoice.NONE;
                };
        String contributedInstructions =
                String.join("\n", context.contribution().instructions());
        String instructions = chatOptions.instructions();
        if (!contributedInstructions.isEmpty()) {
            instructions =
                    instructions == null ? contributedInstructions : instructions + "\n" + contributedInstructions;
        }
        if (chatOptions.toolChoice() == choice && java.util.Objects.equals(instructions, chatOptions.instructions())) {
            return chatOptions;
        }
        return new ChatOptions(
                chatOptions.model(),
                chatOptions.temperature(),
                chatOptions.topP(),
                chatOptions.maxTokens(),
                chatOptions.stop(),
                chatOptions.seed(),
                chatOptions.frequencyPenalty(),
                chatOptions.presencePenalty(),
                choice,
                chatOptions.allowMultipleToolCalls(),
                chatOptions.user(),
                chatOptions.store(),
                chatOptions.conversationId(),
                instructions,
                chatOptions.metadata());
    }

    private static List<Tool> normalizeTools(Collection<? extends Tool> tools) {
        AgentValidation.requireNonNull(tools, "tools");
        return FunctionTools.normalize(tools);
    }

    private static List<ContextProvider> normalizeProviders(Collection<? extends ContextProvider> providers) {
        AgentValidation.requireNonNull(providers, "contextProviders");
        ArrayList<ContextProvider> normalized = new ArrayList<>(providers);
        if (normalized.stream()
                .noneMatch(provider -> provider instanceof HistoryProvider || "history".equals(provider.id()))) {
            normalized.addFirst(new InMemoryHistoryProvider());
        }
        List<ContextProvider> copy = List.copyOf(normalized);
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (ContextProvider provider : copy) {
            AgentValidation.requireNonNull(provider, "contextProvider");
            String id = AgentValidation.requireNonBlank(provider.id(), "context provider id");
            if (!ids.add(id)) {
                throw new com.microsoft.agents.core.ValidationException("Duplicate context provider id '" + id + "'.");
            }
        }
        return copy;
    }

    private static void validateToolChoice(List<Tool> tools, ChatOptions options, List<ContextProvider> providers) {
        if (tools.isEmpty() && options.toolChoice() == ToolChoice.REQUIRED) {
            throw new com.microsoft.agents.core.ValidationException("toolChoice REQUIRED requires at least one tool.");
        }
    }

    private record PreparedRun(
            AgentRunContext context,
            List<ContextProviderRequest> providerRequests,
            List<Message> originalInput,
            List<Tool> tools) {
        private PreparedRun {
            context = AgentValidation.requireNonNull(context, "context");
            providerRequests = List.copyOf(providerRequests);
            originalInput = AgentValidation.copyMessages(originalInput);
            tools = List.copyOf(tools);
        }
    }

    private record SuspendedExecution(LoopExecution execution, FunctionLoopResult suspended, PreparedRun prepared) {}

    private record PreparedResume(LoopExecution execution, PreparedRun prepared) {}

    private static final class PreparationAccumulator {
        private final AgentRunContext original;

        private final AgentSession providerSession;

        private final List<Message> originalInput;

        private final List<Tool> baseTools;

        private final ArrayList<ContextProviderRequest> requests = new ArrayList<>();

        private ContextContribution contribution = ContextContribution.empty();

        private PreparationAccumulator(AgentRunContext original, AgentSession providerSession, List<Tool> baseTools) {
            this.original = original;
            this.providerSession = providerSession;
            this.originalInput = original.inputMessages();
            this.baseTools = baseTools;
        }

        private ArrayList<ContextProviderRequest> requests() {
            return requests;
        }

        private ContextProviderRequest request() {
            ArrayList<Message> messages = new ArrayList<>(contribution.messages());
            messages.addAll(originalInput);
            ArrayList<Tool> effectiveTools = new ArrayList<>(baseTools);
            effectiveTools.addAll(contribution.tools());
            LinkedHashMap<String, com.microsoft.agents.core.StateValue> metadata =
                    new LinkedHashMap<>(original.metadata());
            metadata.putAll(contribution.metadata());
            return new ContextProviderRequest(
                    providerSession, original, messages, contribution.instructions(), metadata, effectiveTools);
        }

        private void append(ContextContribution next) {
            contribution = contribution.append(next);
        }

        private PreparedRun finish() {
            ArrayList<Message> messages = new ArrayList<>(contribution.messages());
            messages.addAll(originalInput);
            LinkedHashMap<String, com.microsoft.agents.core.StateValue> metadata =
                    new LinkedHashMap<>(original.metadata());
            metadata.putAll(contribution.metadata());
            ArrayList<Tool> effectiveTools = new ArrayList<>(baseTools);
            effectiveTools.addAll(contribution.tools());
            List<Tool> normalizedTools = FunctionTools.normalize(effectiveTools);
            AgentRunContext preparedContext = new AgentRunContext(
                    original.runId(),
                    original.agent(),
                    original.startedAt(),
                    messages,
                    original.options(),
                    original.cancellation(),
                    metadata,
                    original.session(),
                    contribution);
            return new PreparedRun(preparedContext, requests, originalInput, normalizedTools);
        }
    }

    private record LoopExecution(
            FunctionInvocationLoop loop,
            FunctionInvocationRun streamingRun,
            CompletionStage<FunctionLoopResult> finiteResult,
            int initialMessageCount) {
        private CompletionStage<FunctionLoopResult> resultAsync() {
            return streamingRun == null ? finiteResult : streamingRun.resultAsync();
        }
    }

    private final class ClientTurnSource implements ToolTurnSource {
        private final AgentRunContext context;

        private ClientTurnSource(AgentRunContext context) {
            this.context = context;
        }

        @Override
        public CompletionStage<ChatResponse> completeAsync(ToolTurnRequest request, RunCancellation cancellation) {
            return chatClient.completeAsync(toClientRequest(request), cancellation);
        }

        @Override
        public Flow.Publisher<ChatResponseUpdate> completeStreaming(
                ToolTurnRequest request, RunCancellation cancellation) {
            return chatClient.completeStreaming(toClientRequest(request), cancellation);
        }

        private ChatClientRequest toClientRequest(ToolTurnRequest request) {
            if (!request.logicalRunId().equals(context.runId())) {
                throw new AgentExecutionException("Tool turn run identity does not match the agent run context.");
            }
            return new ChatClientRequest(
                    request.messages(),
                    optionsFor(request.toolMode(), context),
                    request.tools(),
                    request.toolMode(),
                    context);
        }
    }
}
