// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.AgentFrameworkException;
import com.microsoft.agents.core.ChatResponse;
import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.FinishReason;
import com.microsoft.agents.core.FunctionCallContent;
import com.microsoft.agents.core.FunctionResultContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates provider turns, local function execution, approval interruption, and exactly-once
 * invocation ownership within one uninterrupted logical run.
 *
 * <p>The loop depends only on core models and {@link ToolTurnSource}. Synchronous, asynchronous, and
 * streaming views exposed by {@link FunctionInvocationRun} share one execution owner. The default
 * constructor owns a virtual-thread executor and closes it from {@link #close()}; a caller-provided
 * executor is never closed.
 *
 * <p>In-memory invocation ownership prevents duplicate execution only while this loop and logical run
 * remain uninterrupted. A configured {@link ToolInvocationLedger} supplies ADR-0038 persistence hooks,
 * but crash-safe exactly-once external effects still require atomic checkpoint/ledger storage or
 * provider idempotency.
 *
 * <p>Cancellation completes framework-owned stages with {@link RunCancelledException}, cancels an
 * active provider subscription, and suppresses later turns and terminal success. A synchronous method
 * already running on a caller-owned executor may require cooperative interruption; the framework does
 * not claim that every external call can be forcibly stopped.
 *
 * <p>Run objects retain emitted updates in their bounded publisher so callers can observe both updates
 * and the terminal result. Finite convenience methods that return only a result discard update
 * emissions at their source. The bounded publisher limits framework memory retention and does not
 * imply end-to-end provider transport throttling.
 */
public final class FunctionInvocationLoop implements AutoCloseable {
    private static final String FUNCTION_FAILURE = "Error: Function failed.";

    private static final String ARGUMENT_FAILURE = "Error: Argument parsing failed.";

    private static final String OUTPUT_VALIDATION_FAILURE = "Error: Tool output schema validation failed.";

    private static final String UNKNOWN_FUNCTION = "Error: Requested function was not found.";

    private static final String REJECTED_MESSAGE = "The tool was not executed.";

    private static final String LIMIT_MESSAGE =
            "Function invocation limit reached before a final answer could be produced.";

    private final ToolTurnSource turnSource;

    private final List<Tool> tools;

    private final Map<String, Tool> toolsByName;

    private final Executor executor;

    private final ExecutorService ownedExecutor;

    private final InvocationIdFactory invocationIdFactory;

    private final ToolInvocationLedger ledger;

    private final List<ToolInvocationInterceptor> interceptors;

    private final ConcurrentHashMap<String, LogicalRunState> runs = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a loop that owns a virtual-thread-per-task executor.
     *
     * @param turnSource provider-neutral turn source
     * @param tools available tools
     */
    public FunctionInvocationLoop(ToolTurnSource turnSource, Collection<? extends Tool> tools) {
        this(
                turnSource,
                tools,
                Executors.newVirtualThreadPerTaskExecutor(),
                true,
                InvocationIdFactory.defaultFactory(),
                null,
                List.of());
    }

    /**
     * Creates a loop using a caller-owned executor.
     *
     * @param turnSource provider-neutral turn source
     * @param tools available tools
     * @param executor caller-owned executor, which this loop never closes
     */
    public FunctionInvocationLoop(ToolTurnSource turnSource, Collection<? extends Tool> tools, Executor executor) {
        this(turnSource, tools, executor, false, InvocationIdFactory.defaultFactory(), null, List.of());
    }

    /**
     * Creates a loop with explicit executor, invocation-id, and durable-ledger hooks.
     *
     * @param turnSource provider-neutral turn source
     * @param tools available tools
     * @param executor caller-owned executor, which this loop never closes
     * @param invocationIdFactory invocation identifier factory
     * @param ledger optional durable ledger, or {@code null}
     */
    public FunctionInvocationLoop(
            ToolTurnSource turnSource,
            Collection<? extends Tool> tools,
            Executor executor,
            InvocationIdFactory invocationIdFactory,
            ToolInvocationLedger ledger) {
        this(turnSource, tools, executor, false, invocationIdFactory, ledger, List.of());
    }

    /**
     * Creates a loop with explicit durable-ledger hooks and provider-neutral invocation interceptors.
     *
     * @param turnSource provider-neutral turn source
     * @param tools available tools
     * @param executor caller-owned executor, which this loop never closes
     * @param invocationIdFactory invocation identifier factory
     * @param ledger optional durable ledger, or {@code null}
     * @param interceptors function invocation interceptors in registration order
     */
    public FunctionInvocationLoop(
            ToolTurnSource turnSource,
            Collection<? extends Tool> tools,
            Executor executor,
            InvocationIdFactory invocationIdFactory,
            ToolInvocationLedger ledger,
            Collection<? extends ToolInvocationInterceptor> interceptors) {
        this(turnSource, tools, executor, false, invocationIdFactory, ledger, interceptors);
    }

    private FunctionInvocationLoop(
            ToolTurnSource turnSource,
            Collection<? extends Tool> tools,
            Executor executor,
            boolean ownsExecutor,
            InvocationIdFactory invocationIdFactory,
            ToolInvocationLedger ledger,
            Collection<? extends ToolInvocationInterceptor> interceptors) {
        this.turnSource = Objects.requireNonNull(turnSource, "turnSource");
        this.tools = FunctionTools.normalize(Objects.requireNonNull(tools, "tools"));
        this.toolsByName = FunctionTools.byName(this.tools);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = ownsExecutor ? (ExecutorService) executor : null;
        this.invocationIdFactory = Objects.requireNonNull(invocationIdFactory, "invocationIdFactory");
        this.ledger = ledger;
        Objects.requireNonNull(interceptors, "interceptors");
        this.interceptors = List.copyOf(interceptors);
        if (this.interceptors.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("interceptors contains null");
        }
    }

    /**
     * Starts a finite provider-turn run.
     *
     * <p>The returned run exposes a bounded update stream. Subscribe and drain that stream when
     * observing the result. Call {@link #runAsync(FunctionInvocationRequest)} or {@link
     * #run(FunctionInvocationRequest)} when updates are not needed.
     *
     * @param request logical run request
     * @return shared execution owner
     */
    public FunctionInvocationRun start(FunctionInvocationRequest request) {
        return start(request, false, SingleSubscriberPublisher.UpdateMode.BUFFERED);
    }

    /**
     * Starts a streaming provider-turn run.
     *
     * @param request logical run request
     * @return shared execution owner
     */
    public FunctionInvocationRun startStreaming(FunctionInvocationRequest request) {
        return start(request, true, SingleSubscriberPublisher.UpdateMode.BUFFERED);
    }

    /**
     * Runs a finite provider-turn loop asynchronously.
     *
     * <p>Update emissions are discarded at their source because this convenience method exposes only
     * the terminal result.
     *
     * @param request logical run request
     * @return terminal phase stage
     */
    public CompletionStage<FunctionLoopResult> runAsync(FunctionInvocationRequest request) {
        return startFiniteWithoutUpdates(request).resultAsync();
    }

    /**
     * Runs a finite provider-turn loop synchronously through the same execution owner.
     *
     * <p>Update emissions are discarded at their source because this convenience method exposes only
     * the terminal result. Do not invoke this method from the same saturated caller-owned bounded
     * executor that must execute the run's tool work.
     *
     * @param request logical run request
     * @return terminal phase result
     */
    public FunctionLoopResult run(FunctionInvocationRequest request) {
        return startFiniteWithoutUpdates(request).result();
    }

    /**
     * Returns the update publisher for a streaming provider-turn run.
     *
     * <p>Call {@link #startStreaming(FunctionInvocationRequest)} when both updates and the terminal
     * {@link FunctionLoopResult} are needed.
     *
     * @param request logical run request
     * @return single-subscriber run publisher
     */
    public Flow.Publisher<ChatResponseUpdate> runStreaming(FunctionInvocationRequest request) {
        return startStreaming(request).updates();
    }

    private FunctionInvocationRun startFiniteWithoutUpdates(FunctionInvocationRequest request) {
        return start(request, false, SingleSubscriberPublisher.UpdateMode.DISCARD);
    }

    /**
     * Resumes a suspended run using finite provider turns.
     *
     * @param suspended prior input-required result
     * @param decisions approval decisions
     * @return resumed shared execution owner
     */
    public FunctionInvocationRun resume(FunctionLoopResult suspended, Collection<ToolApprovalDecision> decisions) {
        return resume(suspended, decisions, false);
    }

    /**
     * Resumes a suspended run using streaming provider turns.
     *
     * @param suspended prior input-required result
     * @param decisions approval decisions
     * @return resumed shared execution owner
     */
    public FunctionInvocationRun resumeStreaming(
            FunctionLoopResult suspended, Collection<ToolApprovalDecision> decisions) {
        return resume(suspended, decisions, true);
    }

    /**
     * Restores and resumes safe pending state using finite provider turns.
     *
     * @param continuation detached pending state
     * @param decisions approval decisions
     * @return resumed execution owner
     */
    public FunctionInvocationRun resume(FunctionContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        return resume(continuation, decisions, new DefaultRunCancellation(), false);
    }

    /**
     * Restores and resumes safe pending state using finite provider turns.
     *
     * @param continuation detached pending state
     * @param decisions approval decisions
     * @param cancellation caller-owned cancellation
     * @return resumed execution owner
     */
    public FunctionInvocationRun resume(
            FunctionContinuation continuation,
            Collection<ToolApprovalDecision> decisions,
            RunCancellation cancellation) {
        return resume(continuation, decisions, cancellation, false);
    }

    /**
     * Restores and resumes safe pending state using streaming provider turns.
     *
     * @param continuation detached pending state
     * @param decisions approval decisions
     * @return resumed execution owner
     */
    public FunctionInvocationRun resumeStreaming(
            FunctionContinuation continuation, Collection<ToolApprovalDecision> decisions) {
        return resume(continuation, decisions, new DefaultRunCancellation(), true);
    }

    /**
     * Restores and resumes safe pending state using streaming provider turns.
     *
     * @param continuation detached pending state
     * @param decisions approval decisions
     * @param cancellation caller-owned cancellation
     * @return resumed execution owner
     */
    public FunctionInvocationRun resumeStreaming(
            FunctionContinuation continuation,
            Collection<ToolApprovalDecision> decisions,
            RunCancellation cancellation) {
        return resume(continuation, decisions, cancellation, true);
    }

    /**
     * Releases a completed logical run and its in-memory deduplication records.
     *
     * <p>Releasing a run ends this loop's uninterrupted-run exactly-once boundary.
     *
     * @param logicalRunId logical run identifier
     * @return {@code true} when a completed run was released
     */
    public boolean release(String logicalRunId) {
        ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        LogicalRunState state = runs.get(logicalRunId);
        return state != null && state.isComplete() && runs.remove(logicalRunId, state);
    }

    /**
     * Cancels active work, abandons suspended approval phases, and closes only an owned executor.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        runs.values().forEach(state -> {
            if (state.abandonIfSuspended()) {
                state.cancellation.close();
            } else if (!state.isComplete()) {
                state.cancellation.cancel();
            }
        });
        if (ownedExecutor != null) {
            ownedExecutor.close();
        }
    }

    private FunctionInvocationRun start(
            FunctionInvocationRequest request, boolean streaming, SingleSubscriberPublisher.UpdateMode updateMode) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        LogicalRunState state = new LogicalRunState(this, request);
        LogicalRunState existing = runs.putIfAbsent(request.logicalRunId(), state);
        if (existing != null) {
            throw new ToolInvocationException(
                    "Logical run '" + request.logicalRunId() + "' already exists in this loop.");
        }
        return new FunctionInvocationRun(
                state.cancellation,
                request.options().maxBufferedUpdates(),
                updateMode,
                sink -> closeCancellationAfterExecution(state, executeLoop(state, streaming, sink, List.of())));
    }

    private FunctionInvocationRun resume(
            FunctionLoopResult suspended, Collection<ToolApprovalDecision> decisions, boolean streaming) {
        ensureOpen();
        Objects.requireNonNull(suspended, "suspended");
        Objects.requireNonNull(decisions, "decisions");
        if (suspended.outcome() != FunctionLoopOutcome.INPUT_REQUIRED) {
            throw new ToolInvocationException("Only an input-required result can be resumed.");
        }
        LogicalRunState state = suspended.state;
        if (state.owner != this || runs.get(state.logicalRunId) != state) {
            throw new ToolInvocationException("The suspended result belongs to a different or released loop.");
        }
        List<ToolApprovalDecision> copiedDecisions = List.copyOf(decisions);
        return new FunctionInvocationRun(
                state.cancellation,
                state.options.maxBufferedUpdates(),
                SingleSubscriberPublisher.UpdateMode.BUFFERED,
                sink -> executeResume(state, suspended.suspensionVersion, copiedDecisions, streaming, sink));
    }

    private FunctionInvocationRun resume(
            FunctionContinuation continuation,
            Collection<ToolApprovalDecision> decisions,
            RunCancellation cancellation,
            boolean streaming) {
        ensureOpen();
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(cancellation, "cancellation");
        LogicalRunState state = new LogicalRunState(this, continuation, cancellation);
        LogicalRunState existing = runs.putIfAbsent(continuation.logicalRunId(), state);
        if (existing != null) {
            throw new ToolInvocationException(
                    "Logical run '" + continuation.logicalRunId() + "' already exists in this loop.");
        }
        FunctionLoopResult suspended = state.suspendedResult(continuation.approvalRequests(), List.of());
        try {
            return resume(suspended, decisions, streaming);
        } catch (RuntimeException failure) {
            runs.remove(continuation.logicalRunId(), state);
            state.cancellation.close();
            throw failure;
        }
    }

    private CompletionStage<FunctionLoopResult> executeResume(
            LogicalRunState state,
            long suspensionVersion,
            List<ToolApprovalDecision> decisions,
            boolean streaming,
            SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        ResumePlan plan;
        try {
            plan = state.beginResume(suspensionVersion, decisions);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (!plan.remainingRequests().isEmpty()) {
            return CompletableFuture.completedFuture(
                    state.suspendedResult(plan.remainingRequests(), plan.rejections()));
        }
        CompletionStage<FunctionLoopResult> executionStage = executePendingBatch(
                        state, plan.calls(), plan.rejections(), sink)
                .thenCompose(execution -> {
                    if (!execution.newResults().isEmpty()) {
                        appendToolResults(state, execution.newResults(), sink);
                    }
                    if (execution.onlyRejected()) {
                        state.complete();
                        return CompletableFuture.completedFuture(state.successResult(plan.rejections()));
                    }
                    return executeLoop(state, streaming, sink, plan.rejections());
                });
        return closeCancellationAfterExecution(state, executionStage);
    }

    private static CompletionStage<FunctionLoopResult> closeCancellationAfterExecution(
            LogicalRunState state, CompletionStage<FunctionLoopResult> execution) {
        return execution.whenComplete((result, failure) -> {
            if (failure != null) {
                state.fail();
            }
            if (failure != null || result.outcome() != FunctionLoopOutcome.INPUT_REQUIRED) {
                state.cancellation.close();
            }
        });
    }

    private CompletionStage<FunctionLoopResult> executeLoop(
            LogicalRunState state,
            boolean streaming,
            SingleSubscriberPublisher<ChatResponseUpdate> sink,
            List<ToolApprovalDecisionRejection> rejectedDecisions) {
        if (state.cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (state.modelTurns >= state.options.maxIterations()) {
            return executeFinalTurn(state, streaming, sink, rejectedDecisions);
        }
        ToolTurnRequest request = state.turnRequest(state.currentToolMode());
        return requestTurn(request, state, streaming, false, sink).thenCompose(response -> {
            List<PreparedCall> calls = prepareCalls(state, response);
            ChatResponse recordedResponse = filterDuplicateCallOccurrences(state, response);
            state.recordTurn(recordedResponse);
            if (!streaming) {
                emitResponse(recordedResponse, sink);
            }
            if (calls.isEmpty()) {
                state.complete();
                return CompletableFuture.completedFuture(state.successResult(rejectedDecisions));
            }
            List<ToolApprovalRequest> approvals = createApprovals(state, calls);
            if (!approvals.isEmpty()) {
                return CompletableFuture.completedFuture(state.suspend(calls, approvals, rejectedDecisions));
            }
            return executePendingBatch(state, calls, rejectedDecisions, sink).thenCompose(execution -> {
                if (!execution.newResults().isEmpty()) {
                    appendToolResults(state, execution.newResults(), sink);
                }
                if (execution.newResults().isEmpty()) {
                    state.complete();
                    return CompletableFuture.completedFuture(state.successResult(rejectedDecisions));
                }
                if (state.functionLimitReached()) {
                    return executeFinalTurn(state, streaming, sink, rejectedDecisions);
                }
                return executeLoop(state, streaming, sink, rejectedDecisions);
            });
        });
    }

    private CompletionStage<FunctionLoopResult> executeFinalTurn(
            LogicalRunState state,
            boolean streaming,
            SingleSubscriberPublisher<ChatResponseUpdate> sink,
            List<ToolApprovalDecisionRejection> rejectedDecisions) {
        if (state.cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        ToolTurnRequest request = state.turnRequest(ToolMode.NONE);
        return requestTurn(request, state, streaming, true, sink).thenApply(response -> {
            ChatResponse sanitized = sanitizeFinalResponse(response);
            state.recordTurn(sanitized);
            if (!streaming) {
                emitResponse(sanitized, sink);
            } else if (containsLimitMessage(sanitized) && !containsLimitMessage(response)) {
                sink.emit(ChatResponseUpdate.builder()
                        .role(Role.ASSISTANT)
                        .contents(List.of(new TextContent(LIMIT_MESSAGE)))
                        .finishReason(FinishReason.STOP)
                        .build());
            }
            state.complete();
            return state.successResult(rejectedDecisions);
        });
    }

    private CompletionStage<ChatResponse> requestTurn(
            ToolTurnRequest request,
            LogicalRunState state,
            boolean streaming,
            boolean finalTurn,
            SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        RunCancellation cancellation = state.cancellation;
        if (!streaming) {
            CompletionStage<ChatResponse> stage;
            try {
                stage = turnSource.completeAsync(request, cancellation);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (stage == null) {
                return CompletableFuture.failedFuture(
                        new ToolInvocationException("ToolTurnSource.completeAsync returned null."));
            }
            return withCancellation(stage, cancellation)
                    .thenApply(response -> Objects.requireNonNull(response, "turn response"));
        }
        Flow.Publisher<ChatResponseUpdate> publisher;
        try {
            publisher = turnSource.completeStreaming(request, cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (publisher == null) {
            return CompletableFuture.failedFuture(
                    new ToolInvocationException("ToolTurnSource.completeStreaming returned null."));
        }
        return collectStreamingTurn(publisher, state, finalTurn, sink);
    }

    private static CompletionStage<ChatResponse> collectStreamingTurn(
            Flow.Publisher<ChatResponseUpdate> publisher,
            LogicalRunState state,
            boolean finalTurn,
            SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        RunCancellation cancellation = state.cancellation;
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        ResponseAggregator.ChatAggregation aggregation = ResponseAggregator.chat();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            private com.microsoft.agents.core.RunCancellationRegistration cancellationRegistration;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                if (this.subscription != null) {
                    subscription.cancel();
                    return;
                }
                this.subscription = subscription;
                cancellationRegistration = RunCancellations.register(cancellation, () -> {
                    subscription.cancel();
                    result.completeExceptionally(new RunCancelledException());
                });
                result.whenComplete((ignored, failure) -> cancellationRegistration.close());
                if (cancellation.isCancellationRequested()) {
                    subscription.cancel();
                    result.completeExceptionally(new RunCancelledException());
                    return;
                }
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ChatResponseUpdate item) {
                if (result.isDone() || cancellation.isCancellationRequested()) {
                    subscription.cancel();
                    return;
                }
                try {
                    if (finalTurn) {
                        ChatResponseUpdate effective = sanitizeFinalUpdate(item);
                        if (effective != null) {
                            aggregation.add(effective);
                            sink.emit(effective);
                        }
                    } else {
                        aggregation.add(item);
                        ChatResponseUpdate visible = filterCompletedInvocationUpdate(state, item);
                        if (visible != null) {
                            sink.emit(visible);
                        }
                    }
                } catch (RuntimeException failure) {
                    subscription.cancel();
                    result.completeExceptionally(failure);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                if (cancellation.isCancellationRequested()) {
                    result.completeExceptionally(new RunCancelledException());
                    return;
                }
                try {
                    result.complete(aggregation.isTerminal() ? aggregation.response() : aggregation.finish());
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }
        });
        return result.minimalCompletionStage();
    }

    private static <T> CompletionStage<T> withCancellation(CompletionStage<T> source, RunCancellation cancellation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        var cancellationRegistration = RunCancellations.register(
                cancellation, () -> result.completeExceptionally(new RunCancelledException()));
        result.whenComplete((ignored, failure) -> cancellationRegistration.close());
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
        });
        if (cancellation.isCancellationRequested()) {
            result.completeExceptionally(new RunCancelledException());
        }
        return result.minimalCompletionStage();
    }

    private List<PreparedCall> prepareCalls(LogicalRunState state, ChatResponse response) {
        List<PreparedCall> prepared = new ArrayList<>();
        Set<InvocationId> seenInResponse = new LinkedHashSet<>();
        for (Message message : response.messages()) {
            for (var content : message.contents()) {
                if (!(content instanceof FunctionCallContent call) || call.informationalOnly()) {
                    continue;
                }
                InvocationId invocationId = invocationIdFactory.create(state.logicalRunId, call);
                if (!seenInResponse.add(invocationId)) {
                    PreparedCall existing = prepared.stream()
                            .filter(item -> item.invocationId().equals(invocationId))
                            .findFirst()
                            .orElse(null);
                    if (existing != null) {
                        prepared.add(existing.asDuplicate());
                    }
                    continue;
                }
                Tool candidate = toolsByName.get(call.name());
                FunctionTool functionTool = candidate instanceof FunctionTool function ? function : null;
                StateValue.ObjectValue arguments = null;
                String preparationError = null;
                if (functionTool == null) {
                    preparationError = UNKNOWN_FUNCTION;
                } else {
                    try {
                        arguments = normalizeArguments(call.arguments());
                    } catch (ToolBindingException failure) {
                        preparationError = ARGUMENT_FAILURE;
                    }
                }
                String schemaDigest = functionTool == null
                        ? ToolDigests.strings("unknown-tool", call.name())
                        : ToolDigests.state(functionTool.metadata().inputSchema());
                String argumentsDigest = ToolDigests.state(arguments == null ? call.arguments() : arguments);
                String requestDigest = ToolDigests.strings(
                        state.logicalRunId,
                        call.callId(),
                        invocationId.value(),
                        call.name(),
                        schemaDigest,
                        argumentsDigest);
                prepared.add(new PreparedCall(
                        call,
                        functionTool,
                        arguments,
                        invocationId,
                        requestDigest,
                        preparationError,
                        state.invocations.containsKey(invocationId),
                        null));
            }
        }
        return List.copyOf(prepared);
    }

    private List<ToolApprovalRequest> createApprovals(LogicalRunState state, List<PreparedCall> calls) {
        List<ToolApprovalRequest> requests = new ArrayList<>();
        for (PreparedCall call : calls) {
            if (call.duplicate()
                    || call.tool() == null
                    || call.arguments() == null
                    || call.tool().metadata().approvalMode() != ToolApprovalMode.ALWAYS_REQUIRE) {
                continue;
            }
            ToolInvocationContext context = state.context(call.call(), call.invocationId());
            ToolApprovalRequest request = ToolApprovals.request(context, call.tool(), call.arguments());
            ApprovalSlot slot = state.registerApproval(request);
            call.approval(slot);
            requests.add(request);
        }
        return List.copyOf(requests);
    }

    private static StateValue.ObjectValue normalizeArguments(StateValue arguments) {
        if (arguments instanceof StateValue.ObjectValue object) {
            return object;
        }
        if (arguments instanceof StateValue.StringValue stringValue) {
            return ToolJson.parseObject(stringValue.value());
        }
        if (arguments == StateValue.NullValue.INSTANCE) {
            return StateValue.object(Map.of());
        }
        throw new ToolBindingException("Function arguments must be an object or an encoded JSON object.");
    }

    private ChatResponse filterDuplicateCallOccurrences(LogicalRunState state, ChatResponse response) {
        Set<InvocationId> seen = new LinkedHashSet<>();
        List<Message> messages = new ArrayList<>();
        boolean changed = false;
        for (Message message : response.messages()) {
            List<com.microsoft.agents.core.Content> contents = new ArrayList<>();
            for (var content : message.contents()) {
                if (content instanceof FunctionCallContent call && !call.informationalOnly()) {
                    InvocationId invocationId = invocationIdFactory.create(state.logicalRunId, call);
                    if (state.invocations.containsKey(invocationId) || !seen.add(invocationId)) {
                        changed = true;
                        continue;
                    }
                }
                contents.add(content);
            }
            if (!contents.isEmpty()) {
                messages.add(new Message(
                        message.role(), contents, message.authorName(), message.messageId(), message.metadata()));
            }
        }
        if (!changed) {
            return response;
        }
        return new ChatResponse(
                messages,
                response.responseId(),
                response.conversationId(),
                response.model(),
                response.createdAt(),
                response.finishReason(),
                response.usage(),
                response.continuationToken(),
                response.metadata(),
                response.updateSequences());
    }

    private CompletionStage<BatchExecution> executePendingBatch(
            LogicalRunState state,
            List<PreparedCall> calls,
            List<ToolApprovalDecisionRejection> rejectedDecisions,
            SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        List<CompletionStage<Optional<ToolInvocationResult>>> stages = new ArrayList<>(calls.size());
        boolean onlyRejected = !calls.isEmpty();
        for (PreparedCall call : calls) {
            ApprovalSlot approval = call.approval();
            if (approval != null && approval.decision() == ToolApprovalState.REJECTED) {
                approval.consume();
                stages.add(rejectCall(state, call).thenApply(Optional::of));
                continue;
            }
            if (call.duplicate()) {
                stages.add(executeCall(state, call).thenApply(Optional::of));
                continue;
            }
            onlyRejected = false;
            if (approval != null) {
                approval.consume();
            }
            stages.add(executeCall(state, call).thenApply(Optional::of));
        }
        final boolean batchOnlyRejected = onlyRejected;
        return sequence(stages).thenApply(results -> {
            List<ToolInvocationResult> newResults = new ArrayList<>();
            for (Optional<ToolInvocationResult> optional : results) {
                optional.filter(result -> state.markResultEmitted(result.invocationId()))
                        .ifPresent(newResults::add);
            }
            return new BatchExecution(List.copyOf(newResults), batchOnlyRejected, rejectedDecisions);
        });
    }

    private CompletionStage<ToolInvocationResult> rejectCall(LogicalRunState state, PreparedCall call) {
        InvocationSlot candidate = new InvocationSlot(call.requestDigest());
        InvocationSlot existing = state.invocations.putIfAbsent(call.invocationId(), candidate);
        if (existing != null) {
            if (!existing.requestDigest.equals(call.requestDigest())) {
                return CompletableFuture.failedFuture(new ToolInvocationException("Invocation id '"
                        + call.invocationId()
                        + "' was reused with a different tool, schema, or argument digest."));
            }
            return existing.resultView;
        }

        ToolInvocationResult rejected = ToolInvocationResult.rejected(
                call.invocationId(), call.call().callId(), StateValue.string(REJECTED_MESSAGE));
        CompletionStage<ToolInvocationResult> completion = recordTerminalWithoutInvocation(state, call, rejected);
        completion.whenComplete((result, failure) -> {
            if (failure != null) {
                candidate.result.completeExceptionally(unwrap(failure));
            } else {
                candidate.result.complete(result);
            }
        });
        return candidate.resultView;
    }

    private CompletionStage<ToolInvocationResult> executeCall(LogicalRunState state, PreparedCall call) {
        InvocationSlot candidate = new InvocationSlot(call.requestDigest());
        InvocationSlot existing = state.invocations.putIfAbsent(call.invocationId(), candidate);
        if (existing != null) {
            if (!existing.requestDigest.equals(call.requestDigest())) {
                return CompletableFuture.failedFuture(new ToolInvocationException("Invocation id '"
                        + call.invocationId()
                        + "' was reused with a different tool, schema, or argument digest."));
            }
            return existing.resultView;
        }

        CompletionStage<ToolInvocationResult> execution = beginOwnedInvocation(state, call);
        execution.whenComplete((result, failure) -> {
            if (failure != null) {
                candidate.result.completeExceptionally(unwrap(failure));
            } else {
                candidate.result.complete(result);
            }
        });
        return candidate.resultView;
    }

    private CompletionStage<ToolInvocationResult> beginOwnedInvocation(LogicalRunState state, PreparedCall call) {
        if (state.cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        if (call.preparationError() != null) {
            return CompletableFuture.completedFuture(
                    ToolInvocationResult.failed(call.invocationId(), call.call().callId(), call.preparationError()));
        }
        if (ledger == null) {
            return invokeFunction(state, call);
        }
        return ledger.lookupAsync(call.invocationId())
                .thenCompose(existing -> inspectDurableEntry(state, call, existing));
    }

    private CompletionStage<ToolInvocationResult> inspectDurableEntry(
            LogicalRunState state, PreparedCall call, Optional<VersionedSnapshot<InvocationLedgerEntry>> existing) {
        if (existing.isPresent()) {
            InvocationLedgerEntry entry = existing.orElseThrow().snapshot();
            if (!entry.requestDigest().equals(call.requestDigest())) {
                return CompletableFuture.failedFuture(new ToolInvocationException(
                        "Durable invocation id '" + call.invocationId() + "' has a mismatched request digest."));
            }
            if (entry instanceof InvocationOutcome outcome) {
                return CompletableFuture.completedFuture(outcome.result());
            }
            return CompletableFuture.failedFuture(new ToolInvocationException("Durable invocation '"
                    + call.invocationId()
                    + "' is pending. Crash replay requires atomic checkpoint/ledger storage or provider idempotency."));
        }
        InvocationRecord pending = new InvocationRecord(
                call.invocationId(),
                state.logicalRunId,
                call.call().callId(),
                call.call().name(),
                call.requestDigest());
        return ledger.recordPendingAsync(pending, 0)
                .thenCompose(versioned -> invokeFunction(state, call).thenCompose(result -> ledger.recordOutcomeAsync(
                                new InvocationOutcome(call.invocationId(), call.requestDigest(), result),
                                versioned.revision())
                        .thenApply(ignored -> result)));
    }

    private CompletionStage<ToolInvocationResult> recordTerminalWithoutInvocation(
            LogicalRunState state, PreparedCall call, ToolInvocationResult result) {
        if (ledger == null) {
            return CompletableFuture.completedFuture(result);
        }
        return ledger.lookupAsync(call.invocationId()).thenCompose(existing -> {
            if (existing.isPresent()) {
                InvocationLedgerEntry entry = existing.orElseThrow().snapshot();
                if (!entry.requestDigest().equals(call.requestDigest())) {
                    return CompletableFuture.failedFuture(new ToolInvocationException(
                            "Durable invocation id '" + call.invocationId() + "' has a mismatched request digest."));
                }
                if (entry instanceof InvocationOutcome outcome) {
                    return CompletableFuture.completedFuture(outcome.result());
                }
                return CompletableFuture.failedFuture(new ToolInvocationException("Durable invocation '"
                        + call.invocationId()
                        + "' is pending. Crash replay requires atomic checkpoint/ledger "
                        + "storage or provider idempotency."));
            }
            InvocationRecord pending = new InvocationRecord(
                    call.invocationId(),
                    state.logicalRunId,
                    call.call().callId(),
                    call.call().name(),
                    call.requestDigest());
            return ledger.recordPendingAsync(pending, 0).thenCompose(versioned -> ledger.recordOutcomeAsync(
                            new InvocationOutcome(call.invocationId(), call.requestDigest(), result),
                            versioned.revision())
                    .thenApply(ignored -> result));
        });
    }

    private CompletionStage<ToolInvocationResult> invokeFunction(LogicalRunState state, PreparedCall call) {
        if (!state.tryStartToolInvocation()) {
            return CompletableFuture.completedFuture(ToolInvocationResult.failed(
                    call.invocationId(), call.call().callId(), "Error: Function invocation limit reached."));
        }
        FunctionTool tool = Objects.requireNonNull(call.tool(), "tool");
        StateValue.ObjectValue arguments = Objects.requireNonNull(call.arguments(), "arguments");
        CompletionStage<StateValue> stage;
        try {
            ToolInvocationInterceptContext context = new ToolInvocationInterceptContext(
                    tool, state.context(call.call(), call.invocationId()), arguments);
            stage = invokeIntercepted(0, context);
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            return CompletableFuture.failedFuture(
                    new ToolInvocationException("FunctionTool.invokeAsync returned null for '" + tool.name() + "'."));
        }
        return withCancellation(stage, state.cancellation).handle((value, failure) -> {
            if (failure == null) {
                return ToolInvocationResult.succeeded(
                        call.invocationId(), call.call().callId(), Objects.requireNonNull(value, "tool result"));
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof RunCancelledException cancelled) {
                throw new CompletionException(cancelled);
            }
            if (cause instanceof ToolInvocationException invocationFailure) {
                throw new CompletionException(invocationFailure);
            }
            if (cause instanceof ToolOutputValidationException outputFailure) {
                String message = OUTPUT_VALIDATION_FAILURE;
                if (state.options.includeDetailedErrors() && outputFailure.getMessage() != null) {
                    message += " Exception: " + outputFailure.getMessage();
                }
                return ToolInvocationResult.outputValidationFailed(
                        call.invocationId(), call.call().callId(), message);
            }
            if (cause instanceof ToolBindingException bindingFailure) {
                String message = ARGUMENT_FAILURE;
                if (state.options.includeDetailedErrors() && bindingFailure.getMessage() != null) {
                    message += " Exception: " + bindingFailure.getMessage();
                }
                return ToolInvocationResult.failed(
                        call.invocationId(), call.call().callId(), message);
            }
            if (cause instanceof ToolUserException userFailure) {
                String message = FUNCTION_FAILURE;
                if (state.options.includeDetailedErrors() && userFailure.getMessage() != null) {
                    message += " Exception: " + userFailure.getMessage();
                }
                return ToolInvocationResult.failed(
                        call.invocationId(), call.call().callId(), message);
            }
            if (cause instanceof AgentFrameworkException frameworkFailure) {
                throw new CompletionException(frameworkFailure);
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        });
    }

    private CompletionStage<StateValue> invokeIntercepted(int index, ToolInvocationInterceptContext context) {
        if (index >= interceptors.size()) {
            return context.tool().invokeAsync(context.invocation(), context.arguments());
        }
        ToolInvocationInterceptor interceptor = interceptors.get(index);
        AtomicBoolean proceeded = new AtomicBoolean();
        ToolInvocationInterceptorChain chain = nextContext -> {
            if (!proceeded.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new ToolInvocationException("A tool invocation interceptor called its chain more than once."));
            }
            return invokeIntercepted(index + 1, Objects.requireNonNull(nextContext, "context"));
        };
        CompletionStage<StateValue> stage = interceptor.interceptAsync(context, chain);
        if (stage == null) {
            return CompletableFuture.failedFuture(
                    new ToolInvocationException("ToolInvocationInterceptor.interceptAsync returned null."));
        }
        return stage;
    }

    private static void appendToolResults(
            LogicalRunState state,
            List<ToolInvocationResult> results,
            SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        List<FunctionResultContent> contents = results.stream()
                .map(result -> new FunctionResultContent(
                        result.callId(),
                        result.value(),
                        List.of(),
                        result.error(),
                        Map.of(
                                "invocationId",
                                StateValue.string(result.invocationId().value()),
                                "outcome",
                                StateValue.string(outcomeValue(result.outcome())))))
                .toList();
        if (contents.isEmpty()) {
            return;
        }
        Message message = new Message(Role.TOOL, contents);
        state.appendMessage(message);
        sink.emit(
                ChatResponseUpdate.builder().role(Role.TOOL).contents(contents).build());
    }

    private static String outcomeValue(ToolInvocationOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "succeeded";
            case FAILED -> "failed";
            case OUTPUT_VALIDATION_FAILED -> "outputValidationFailed";
            case CANCELLED -> "cancelled";
            case REJECTED -> "rejected";
            case DUPLICATE -> "duplicate";
        };
    }

    private static void emitResponse(ChatResponse response, SingleSubscriberPublisher<ChatResponseUpdate> sink) {
        for (Message message : response.messages()) {
            sink.emit(ChatResponseUpdate.builder()
                    .role(message.role())
                    .contents(message.contents())
                    .responseId(response.responseId())
                    .conversationId(response.conversationId())
                    .model(response.model())
                    .build());
        }
    }

    private static ChatResponse sanitizeFinalResponse(ChatResponse response) {
        List<Message> sanitized = new ArrayList<>();
        boolean visible = false;
        for (Message message : response.messages()) {
            List<com.microsoft.agents.core.Content> contents = message.contents().stream()
                    .filter(content -> !(content instanceof FunctionCallContent call) || call.informationalOnly())
                    .toList();
            if (!contents.isEmpty()) {
                Message copy = new Message(
                        message.role(), contents, message.authorName(), message.messageId(), message.metadata());
                sanitized.add(copy);
                visible |= !copy.text().isBlank()
                        || contents.stream().anyMatch(content -> !(content instanceof TextContent));
            }
        }
        if (!visible) {
            sanitized.add(Message.text(Role.ASSISTANT, LIMIT_MESSAGE));
        }
        return new ChatResponse(
                sanitized,
                response.responseId(),
                response.conversationId(),
                response.model(),
                response.createdAt(),
                response.finishReason() == null ? FinishReason.STOP : response.finishReason(),
                response.usage(),
                response.continuationToken(),
                response.metadata(),
                response.updateSequences());
    }

    private static ChatResponseUpdate sanitizeFinalUpdate(ChatResponseUpdate update) {
        List<com.microsoft.agents.core.Content> contents = update.contents().stream()
                .filter(content -> !(content instanceof FunctionCallContent call) || call.informationalOnly())
                .toList();
        boolean meaningfulMetadata = update.sequence() != null
                || update.authorName() != null
                || update.responseId() != null
                || update.messageId() != null
                || update.conversationId() != null
                || update.model() != null
                || update.createdAt() != null
                || update.finishReason() != null
                || update.usage() != null
                || update.continuationToken() != null
                || !update.metadata().isEmpty();
        if (contents.isEmpty() && !meaningfulMetadata) {
            return null;
        }
        return new ChatResponseUpdate(
                update.sequence(),
                contents,
                update.role(),
                update.authorName(),
                update.responseId(),
                update.messageId(),
                update.conversationId(),
                update.model(),
                update.createdAt(),
                update.finishReason(),
                update.usage(),
                update.continuationToken(),
                update.metadata());
    }

    private static ChatResponseUpdate filterCompletedInvocationUpdate(
            LogicalRunState state, ChatResponseUpdate update) {
        List<com.microsoft.agents.core.Content> contents = update.contents().stream()
                .filter(content -> {
                    if (!(content instanceof FunctionCallContent call) || call.informationalOnly()) {
                        return true;
                    }
                    InvocationId invocationId = state.owner.invocationIdFactory.create(state.logicalRunId, call);
                    return !state.invocations.containsKey(invocationId);
                })
                .toList();
        if (contents.size() == update.contents().size()) {
            return update;
        }
        boolean meaningfulMetadata = update.sequence() != null
                || update.authorName() != null
                || update.responseId() != null
                || update.messageId() != null
                || update.conversationId() != null
                || update.model() != null
                || update.createdAt() != null
                || update.finishReason() != null
                || update.usage() != null
                || update.continuationToken() != null
                || !update.metadata().isEmpty();
        if (contents.isEmpty() && !meaningfulMetadata) {
            return null;
        }
        return new ChatResponseUpdate(
                update.sequence(),
                contents,
                update.role(),
                update.authorName(),
                update.responseId(),
                update.messageId(),
                update.conversationId(),
                update.model(),
                update.createdAt(),
                update.finishReason(),
                update.usage(),
                update.continuationToken(),
                update.metadata());
    }

    private static boolean containsLimitMessage(ChatResponse response) {
        return response.messages().stream()
                .flatMap(message -> message.contents().stream())
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .anyMatch(content -> LIMIT_MESSAGE.equals(content.text()));
    }

    private static <T> CompletionStage<List<T>> sequence(List<? extends CompletionStage<? extends T>> stages) {
        CompletableFuture<?>[] futures =
                stages.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenApply(ignored -> java.util.Arrays.stream(futures)
                .map(CompletableFuture::join)
                .map(value -> {
                    @SuppressWarnings("unchecked")
                    T cast = (T) value;
                    return cast;
                })
                .toList());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FunctionInvocationLoop is closed.");
        }
    }

    private record BatchExecution(
            List<ToolInvocationResult> newResults,
            boolean onlyRejected,
            List<ToolApprovalDecisionRejection> rejectedDecisions) {}

    private record ResumePlan(
            List<PreparedCall> calls,
            List<ToolApprovalRequest> remainingRequests,
            List<ToolApprovalDecisionRejection> rejections) {}

    private static final class InvocationSlot {
        private final String requestDigest;

        private final CompletableFuture<ToolInvocationResult> result = new CompletableFuture<>();

        private final CompletionStage<ToolInvocationResult> resultView = result.minimalCompletionStage();

        private InvocationSlot(String requestDigest) {
            this.requestDigest = requestDigest;
        }
    }

    private static final class ApprovalSlot {
        private final ToolApprovalRequest request;

        private ToolApprovalDecision decision;

        private boolean consumed;

        private ApprovalSlot(ToolApprovalRequest request) {
            this.request = request;
        }

        synchronized ToolApprovalState decision() {
            return decision == null ? null : decision.state();
        }

        synchronized void accept(ToolApprovalDecision decision) {
            this.decision = Objects.requireNonNull(decision, "decision");
        }

        synchronized ToolApprovalDecision acceptedDecision() {
            return decision;
        }

        synchronized boolean consumed() {
            return consumed;
        }

        synchronized void consume() {
            consumed = true;
        }
    }

    static final class LogicalRunState {
        private final FunctionInvocationLoop owner;

        private final String logicalRunId;

        private final FunctionInvocationOptions options;

        private final RunCancellationScope cancellation;

        private final Map<String, StateValue> metadata;

        private final List<Message> history = new ArrayList<>();

        private final Map<InvocationId, InvocationSlot> invocations = new ConcurrentHashMap<>();

        private final Map<ToolApprovalId, ApprovalSlot> approvals = new LinkedHashMap<>();

        private final Set<InvocationId> emittedResults = new LinkedHashSet<>();

        private List<PreparedCall> pendingCalls = List.of();

        private Phase phase = Phase.RUNNING;

        private ToolMode toolMode;

        private long suspensionVersion;

        private int modelTurns;

        private int toolInvocations;

        private ChatResponse latestResponse;

        private UsageDetails usage;

        private LogicalRunState(FunctionInvocationLoop owner, FunctionInvocationRequest request) {
            this.owner = owner;
            this.logicalRunId = request.logicalRunId();
            this.options = request.options();
            this.cancellation = new RunCancellationScope(request.cancellation());
            this.metadata = request.metadata();
            this.history.addAll(request.messages());
            this.toolMode = request.options().toolMode();
        }

        private LogicalRunState(
                FunctionInvocationLoop owner, FunctionContinuation continuation, RunCancellation cancellation) {
            this.owner = owner;
            this.logicalRunId = continuation.logicalRunId();
            this.options = continuation.options();
            this.cancellation = new RunCancellationScope(cancellation);
            this.metadata = continuation.metadata();
            this.history.addAll(continuation.history());
            this.toolMode = continuation.toolMode();
            this.suspensionVersion = continuation.suspensionVersion();
            this.modelTurns = continuation.modelTurns();
            this.toolInvocations = continuation.toolInvocations();
            this.latestResponse = continuation.latestResponse();
            this.usage = continuation.usage();
            this.phase = Phase.SUSPENDED;
            ArrayList<PreparedCall> restoredCalls =
                    new ArrayList<>(continuation.pendingCalls().size());
            for (FunctionContinuationCall pending : continuation.pendingCalls()) {
                Tool candidate = owner.toolsByName.get(pending.call().name());
                FunctionTool functionTool = candidate instanceof FunctionTool function ? function : null;
                String currentDigest = currentRequestDigest(pending, functionTool);
                if (!pending.requestDigest().equals(currentDigest)) {
                    throw new ToolInvocationException("Pending invocation '"
                            + pending.invocationId()
                            + "' no longer matches the configured tool schema or arguments.");
                }
                PreparedCall restored = new PreparedCall(
                        pending.call(),
                        functionTool,
                        pending.arguments(),
                        pending.invocationId(),
                        pending.requestDigest(),
                        pending.preparationError(),
                        pending.duplicate(),
                        null);
                if (pending.approvalRequest() != null) {
                    ApprovalSlot slot = registerApproval(pending.approvalRequest());
                    if (pending.approvalDecision() != null) {
                        slot.accept(pending.approvalDecision());
                    }
                    restored.approval(slot);
                }
                restoredCalls.add(restored);
            }
            this.pendingCalls = List.copyOf(restoredCalls);
        }

        private String currentRequestDigest(FunctionContinuationCall pending, FunctionTool functionTool) {
            String schemaDigest = functionTool == null
                    ? ToolDigests.strings("unknown-tool", pending.call().name())
                    : ToolDigests.state(functionTool.metadata().inputSchema());
            String argumentsDigest = ToolDigests.state(
                    pending.arguments() == null ? pending.call().arguments() : pending.arguments());
            return ToolDigests.strings(
                    logicalRunId,
                    pending.call().callId(),
                    pending.invocationId().value(),
                    pending.call().name(),
                    schemaDigest,
                    argumentsDigest);
        }

        synchronized ToolTurnRequest turnRequest(ToolMode mode) {
            List<ToolMetadata> declarations =
                    owner.tools.stream().map(Tool::metadata).toList();
            return new ToolTurnRequest(logicalRunId, history, declarations, mode, metadata);
        }

        synchronized ToolMode currentToolMode() {
            return toolMode;
        }

        synchronized ToolInvocationContext context(FunctionCallContent call, InvocationId invocationId) {
            return new ToolInvocationContext(
                    logicalRunId, call.callId(), invocationId, cancellation, owner.executor, metadata);
        }

        synchronized void recordTurn(ChatResponse response) {
            requireRunning();
            modelTurns++;
            history.addAll(response.messages());
            latestResponse = response;
            if (response.usage() != null) {
                usage = usage == null ? response.usage() : usage.fold(response.usage());
            }
        }

        synchronized void appendMessage(Message message) {
            history.add(Objects.requireNonNull(message, "message"));
        }

        synchronized ApprovalSlot registerApproval(ToolApprovalRequest request) {
            ApprovalSlot existing = approvals.get(request.approvalId());
            if (existing != null) {
                if (!existing.request.requestDigest().equals(request.requestDigest())) {
                    throw new ToolInvocationException("Approval id collision for '" + request.approvalId() + "'.");
                }
                return existing;
            }
            ApprovalSlot created = new ApprovalSlot(request);
            approvals.put(request.approvalId(), created);
            return created;
        }

        synchronized FunctionLoopResult suspend(
                List<PreparedCall> calls,
                List<ToolApprovalRequest> requests,
                List<ToolApprovalDecisionRejection> rejections) {
            requireRunning();
            pendingCalls = List.copyOf(calls);
            phase = Phase.SUSPENDED;
            suspensionVersion++;
            return new FunctionLoopResult(
                    FunctionLoopOutcome.INPUT_REQUIRED,
                    logicalRunId,
                    history,
                    requests,
                    rejections,
                    modelTurns,
                    toolInvocations,
                    latestResponse,
                    usage,
                    this,
                    suspensionVersion);
        }

        synchronized ResumePlan beginResume(long expectedSuspensionVersion, List<ToolApprovalDecision> decisions) {
            if (phase != Phase.SUSPENDED || expectedSuspensionVersion != suspensionVersion) {
                throw new ToolInvocationException(
                        "Approval resume is stale, already in progress, or already consumed for run '"
                                + logicalRunId
                                + "'.");
            }
            phase = Phase.RESUMING;
            List<ToolApprovalDecisionRejection> rejections = new ArrayList<>();
            Set<ToolApprovalId> acceptedThisResume = new LinkedHashSet<>();
            for (ToolApprovalDecision decision : decisions) {
                ApprovalSlot slot = approvals.get(decision.approvalId());
                if (slot == null) {
                    rejections.add(rejection(
                            decision,
                            ToolApprovalDecisionRejectionReason.STALE_APPROVAL,
                            "Approval id is not pending in this logical run."));
                    continue;
                }
                if (slot.consumed()) {
                    rejections.add(rejection(
                            decision,
                            ToolApprovalDecisionRejectionReason.AUTHORITY_CONSUMED,
                            "Approval authority was already consumed."));
                    continue;
                }
                if (!slot.request.invocationId().equals(decision.invocationId())
                        || !slot.request.requestDigest().equals(decision.requestDigest())) {
                    rejections.add(rejection(
                            decision,
                            ToolApprovalDecisionRejectionReason.MISMATCHED_REQUEST,
                            "Decision invocation id or request digest does not match the issued request."));
                    continue;
                }
                if (slot.decision() != null || !acceptedThisResume.add(decision.approvalId())) {
                    rejections.add(rejection(
                            decision,
                            ToolApprovalDecisionRejectionReason.DECISION_ALREADY_PENDING,
                            "A decision was already accepted for this pending approval."));
                    continue;
                }
                slot.accept(decision);
            }

            List<ToolApprovalRequest> remaining = approvals.values().stream()
                    .filter(slot -> !slot.consumed() && slot.decision() == null)
                    .map(slot -> slot.request)
                    .toList();
            if (!remaining.isEmpty()) {
                phase = Phase.SUSPENDED;
                suspensionVersion++;
                return new ResumePlan(List.of(), remaining, List.copyOf(rejections));
            }
            phase = Phase.RUNNING;
            List<PreparedCall> calls = pendingCalls;
            pendingCalls = List.of();
            return new ResumePlan(calls, List.of(), List.copyOf(rejections));
        }

        synchronized FunctionLoopResult suspendedResult(
                List<ToolApprovalRequest> requests, List<ToolApprovalDecisionRejection> rejections) {
            if (phase != Phase.SUSPENDED) {
                throw new IllegalStateException("Logical run is not suspended.");
            }
            return new FunctionLoopResult(
                    FunctionLoopOutcome.INPUT_REQUIRED,
                    logicalRunId,
                    history,
                    requests,
                    rejections,
                    modelTurns,
                    toolInvocations,
                    latestResponse,
                    usage,
                    this,
                    suspensionVersion);
        }

        synchronized FunctionContinuation continuation() {
            if (phase != Phase.SUSPENDED) {
                throw new ToolInvocationException("Logical run is not suspended.");
            }
            List<ToolApprovalRequest> pendingRequests = approvals.values().stream()
                    .filter(slot -> !slot.consumed() && slot.decision() == null)
                    .map(slot -> slot.request)
                    .toList();
            List<FunctionContinuationCall> calls = pendingCalls.stream()
                    .map(call -> new FunctionContinuationCall(
                            call.call(),
                            call.invocationId(),
                            call.requestDigest(),
                            call.arguments(),
                            call.preparationError(),
                            call.duplicate(),
                            call.approval() == null ? null : call.approval().request,
                            call.approval() == null ? null : call.approval().acceptedDecision()))
                    .toList();
            return new FunctionContinuation(
                    logicalRunId,
                    history,
                    pendingRequests,
                    calls,
                    options,
                    metadata,
                    toolMode,
                    suspensionVersion,
                    modelTurns,
                    toolInvocations,
                    latestResponse,
                    usage);
        }

        synchronized boolean markResultEmitted(InvocationId invocationId) {
            return emittedResults.add(invocationId);
        }

        synchronized boolean tryStartToolInvocation() {
            if (options.maxFunctionCalls() != null && toolInvocations >= options.maxFunctionCalls()) {
                toolMode = ToolMode.NONE;
                return false;
            }
            toolInvocations++;
            if (options.maxFunctionCalls() != null && toolInvocations >= options.maxFunctionCalls()) {
                toolMode = ToolMode.NONE;
            }
            return true;
        }

        synchronized boolean functionLimitReached() {
            return options.maxFunctionCalls() != null && toolInvocations >= options.maxFunctionCalls();
        }

        synchronized void complete() {
            phase = Phase.COMPLETE;
        }

        synchronized void fail() {
            phase = Phase.COMPLETE;
        }

        synchronized boolean isComplete() {
            return phase == Phase.COMPLETE;
        }

        synchronized boolean abandonIfSuspended() {
            if (phase != Phase.SUSPENDED) {
                return false;
            }
            phase = Phase.COMPLETE;
            return true;
        }

        synchronized FunctionLoopResult successResult(List<ToolApprovalDecisionRejection> rejections) {
            if (phase != Phase.COMPLETE) {
                throw new IllegalStateException("Logical run is not complete.");
            }
            return new FunctionLoopResult(
                    FunctionLoopOutcome.SUCCESS,
                    logicalRunId,
                    history,
                    List.of(),
                    rejections,
                    modelTurns,
                    toolInvocations,
                    latestResponse,
                    usage,
                    this,
                    suspensionVersion);
        }

        private void requireRunning() {
            if (phase != Phase.RUNNING) {
                throw new IllegalStateException("Logical run is not running.");
            }
        }

        private static ToolApprovalDecisionRejection rejection(
                ToolApprovalDecision decision, ToolApprovalDecisionRejectionReason reason, String message) {
            return new ToolApprovalDecisionRejection(decision, reason, message);
        }

        private enum Phase {
            RUNNING,
            SUSPENDED,
            RESUMING,
            COMPLETE
        }
    }

    private static final class PreparedCall {
        private final FunctionCallContent call;

        private final FunctionTool tool;

        private final StateValue.ObjectValue arguments;

        private final InvocationId invocationId;

        private final String requestDigest;

        private final String preparationError;

        private final boolean duplicate;

        private ApprovalSlot approval;

        private PreparedCall(
                FunctionCallContent call,
                FunctionTool tool,
                StateValue.ObjectValue arguments,
                InvocationId invocationId,
                String requestDigest,
                String preparationError,
                boolean duplicate,
                ApprovalSlot approval) {
            this.call = call;
            this.tool = tool;
            this.arguments = arguments;
            this.invocationId = invocationId;
            this.requestDigest = requestDigest;
            this.preparationError = preparationError;
            this.duplicate = duplicate;
            this.approval = approval;
        }

        FunctionCallContent call() {
            return call;
        }

        FunctionTool tool() {
            return tool;
        }

        StateValue.ObjectValue arguments() {
            return arguments;
        }

        InvocationId invocationId() {
            return invocationId;
        }

        String requestDigest() {
            return requestDigest;
        }

        String preparationError() {
            return preparationError;
        }

        boolean duplicate() {
            return duplicate;
        }

        PreparedCall asDuplicate() {
            return new PreparedCall(
                    call, tool, arguments, invocationId, requestDigest, preparationError, true, approval);
        }

        ApprovalSlot approval() {
            return approval;
        }

        void approval(ApprovalSlot approval) {
            this.approval = approval;
        }
    }
}
