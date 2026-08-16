// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.harness;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentRunResult;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.AgentSessionSnapshot;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.BaseAgent;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentExecutionException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ResponseAggregator;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import com.microsoft.agents.tools.ToolApprovalDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reinvokes one session-aware {@link ChatAgent} until every evaluator stops or a hard cap is reached.
 *
 * <p>The loop stops immediately at an approval continuation boundary. It never auto-approves a
 * pending tool invocation.
 */
public final class LoopAgent extends BaseAgent<Void> {
    private static final int MAX_BUFFERED_UPDATES = 256;

    private final ChatAgent inner;

    private final List<LoopEvaluator> evaluators;

    private final LoopAgentOptions options;

    private final boolean closeInner;

    private final ConcurrentHashMap<String, ProcessLocalContinuation> processLocalContinuations =
            new ConcurrentHashMap<>();

    /**
     * Creates a non-owning loop decorator.
     *
     * @param inner caller-owned chat agent
     * @param evaluators ordered loop evaluators
     */
    public LoopAgent(ChatAgent inner, List<? extends LoopEvaluator> evaluators) {
        this(inner, evaluators, LoopAgentOptions.defaults(), false);
    }

    /**
     * Creates a configured loop decorator.
     *
     * @param inner wrapped chat agent
     * @param evaluators ordered loop evaluators
     * @param options loop options
     * @param closeInner whether closing the loop closes the wrapped agent
     */
    public LoopAgent(
            ChatAgent inner, List<? extends LoopEvaluator> evaluators, LoopAgentOptions options, boolean closeInner) {
        super(Objects.requireNonNull(inner, "inner").metadata());
        this.inner = inner;
        this.evaluators = List.copyOf(Objects.requireNonNull(evaluators, "evaluators"));
        if (this.evaluators.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("evaluators contains null");
        }
        this.options = Objects.requireNonNull(options, "options");
        this.closeInner = closeInner;
    }

    /** Returns the wrapped chat agent. */
    public ChatAgent chatAgent() {
        return inner;
    }

    /** Returns ordered evaluators. */
    public List<LoopEvaluator> evaluators() {
        return evaluators;
    }

    /** Creates a session through the wrapped chat agent. */
    public CompletionStage<AgentSession> createSessionAsync() {
        return inner.createSessionAsync();
    }

    /** Creates a session synchronously through the wrapped chat agent. */
    public AgentSession createSession() {
        return inner.createSession();
    }

    /**
     * Runs a bounded loop against one caller-owned session.
     *
     * @param session active session
     * @param messages ordered input messages
     * @param runOptions run options
     * @param cancellation caller-owned cancellation
     * @return completed or approval-required result
     */
    public CompletionStage<AgentRunResult<Void>> runAsync(
            AgentSession session, List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        return toRunResult(
                startRunWithSession(messages, runOptions, cancellation, Objects.requireNonNull(session, "session"))
                        .resultAsync());
    }

    /**
     * Streams a bounded loop against one caller-owned session.
     *
     * @param session active session
     * @param messages ordered input messages
     * @param runOptions run options
     * @param cancellation caller-owned cancellation
     * @return cold update publisher
     */
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            AgentSession session, List<Message> messages, RunOptions runOptions, RunCancellation cancellation) {
        return runStreamingWithSession(messages, runOptions, cancellation, Objects.requireNonNull(session, "session"));
    }

    /** Returns a pending continuation from the wrapped chat agent. */
    public Optional<AgentContinuation> pendingContinuation(AgentSession session) {
        return inner.pendingContinuation(session);
    }

    /** Resumes a wrapped-agent approval continuation. */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentContinuation continuation, List<ToolApprovalDecision> decisions) {
        AgentContinuation safeContinuation = Objects.requireNonNull(continuation, "continuation");
        ProcessLocalContinuation retained = removeProcessLocalContinuation(safeContinuation.continuationId());
        if (retained == null) {
            return inner.resumeAsync(safeContinuation, decisions);
        }
        AgentSession processLocal = retained.session();
        CompletionStage<AgentRunResult<Void>> resumed;
        try {
            resumed = inner.resumeAsync(processLocal, safeContinuation, decisions);
        } catch (RuntimeException failure) {
            rememberResumedContinuation(safeContinuation, processLocal);
            return CompletableFuture.failedFuture(failure);
        }
        return resumed.whenComplete((result, failure) -> {
            if (failure != null) {
                inner.pendingContinuation(processLocal)
                        .ifPresent(pending -> rememberResumedContinuation(pending, processLocal));
                return;
            }
            result.continuation().ifPresent(pending -> rememberResumedContinuation(pending, processLocal));
        });
    }

    /** Resumes a session-bound wrapped-agent approval continuation. */
    public CompletionStage<AgentRunResult<Void>> resumeAsync(
            AgentSession session, AgentContinuation continuation, List<ToolApprovalDecision> decisions) {
        return inner.resumeAsync(session, continuation, decisions);
    }

    /** Discards one process-local approval continuation. */
    public boolean discardContinuation(AgentContinuation continuation) {
        AgentContinuation safeContinuation = Objects.requireNonNull(continuation, "continuation");
        ProcessLocalContinuation removed = removeProcessLocalContinuation(safeContinuation.continuationId());
        return removed != null || inner.discardContinuation(safeContinuation);
    }

    @Override
    protected CompletionStage<AgentResponse<Void>> executeAsync(com.microsoft.agents.agents.AgentRunContext context) {
        boolean processLocal = context.session() == null;
        AgentSession session =
                processLocal ? AgentSession.processLocal("harness-loop-" + context.runId()) : context.session();
        AgentSession.RunLease lease = session.acquireRunLease();
        try {
            LoopState state = new LoopState(
                    session,
                    lease,
                    session.snapshot(),
                    context.inputMessages(),
                    context.options(),
                    context.cancellation(),
                    effectiveMaxIterations(context.options()));
            return runIteration(state, context.inputMessages()).whenComplete((ignored, failure) -> {
                if (processLocal && failure != null) {
                    rememberProcessLocalContinuation(session, failure, context.cancellation());
                }
                lease.close();
            });
        } catch (RuntimeException failure) {
            lease.close();
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    protected StreamingExecution<Void> executeStreaming(com.microsoft.agents.agents.AgentRunContext context) {
        boolean processLocal = context.session() == null;
        AgentSession session =
                processLocal ? AgentSession.processLocal("harness-loop-" + context.runId()) : context.session();
        AgentSession.RunLease lease = session.acquireRunLease();
        try {
            LoopState state = new LoopState(
                    session,
                    lease,
                    session.snapshot(),
                    context.inputMessages(),
                    context.options(),
                    context.cancellation(),
                    effectiveMaxIterations(context.options()));
            CompletableFuture<AgentResponse<Void>> terminal = new CompletableFuture<>();
            terminal.whenComplete((ignored, failure) -> {
                if (processLocal && failure != null) {
                    rememberProcessLocalContinuation(session, failure, context.cancellation());
                }
                lease.close();
            });
            AtomicReference<Flow.Subscription> upstream = new AtomicReference<>();
            AtomicReference<SingleSubscriberPublisher<AgentResponseUpdate>> sinkReference = new AtomicReference<>();
            AtomicBoolean finished = new AtomicBoolean();
            SingleSubscriberPublisher<AgentResponseUpdate> sink = new SingleSubscriberPublisher<>(
                    () -> streamIteration(
                            state, context.inputMessages(), sinkReference.get(), upstream, terminal, finished),
                    () -> {
                        context.cancellation().cancel();
                        failStream(
                                state, new RunCancelledException(), sinkReference.get(), terminal, upstream, finished);
                    },
                    MAX_BUFFERED_UPDATES,
                    SingleSubscriberPublisher.UpdateMode.BUFFERED,
                    limit -> new AgentExecutionException(
                            "Streaming update buffer exceeded maxBufferedUpdates=" + limit + "."),
                    () -> {
                        context.cancellation().cancel();
                        failStream(
                                state,
                                new AgentExecutionException("Streaming update buffer exceeded maxBufferedUpdates="
                                        + MAX_BUFFERED_UPDATES
                                        + "."),
                                sinkReference.get(),
                                terminal,
                                upstream,
                                finished);
                    });
            sinkReference.set(sink);
            return new StreamingExecution<>(sink, terminal.minimalCompletionStage());
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    @Override
    protected void closeResources() {
        processLocalContinuations.forEach(this::removeProcessLocalContinuation);
        if (closeInner) {
            inner.close();
        }
    }

    private void rememberProcessLocalContinuation(
            AgentSession session, Throwable failure, RunCancellation cancellation) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof ApprovalRequiredException approval) {
            String continuationId = approval.continuation().continuationId();
            ProcessLocalContinuation retained = rememberContinuation(continuationId, session);
            RunCancellationRegistration registration = RunCancellations.register(
                    cancellation, () -> removeProcessLocalContinuation(continuationId, retained));
            retained.attach(registration);
            if (isClosed()) {
                removeProcessLocalContinuation(continuationId, retained);
            }
        }
    }

    private void rememberResumedContinuation(AgentContinuation continuation, AgentSession session) {
        String continuationId = continuation.continuationId();
        ProcessLocalContinuation retained = rememberContinuation(continuationId, session);
        if (isClosed()) {
            removeProcessLocalContinuation(continuationId, retained);
        }
    }

    int processLocalContinuationCountForDiagnostics() {
        return processLocalContinuations.size();
    }

    private ProcessLocalContinuation rememberContinuation(String continuationId, AgentSession session) {
        ProcessLocalContinuation retained = new ProcessLocalContinuation(session);
        ProcessLocalContinuation previous = processLocalContinuations.put(continuationId, retained);
        if (previous != null) {
            previous.close();
        }
        return retained;
    }

    private ProcessLocalContinuation removeProcessLocalContinuation(String continuationId) {
        ProcessLocalContinuation retained = processLocalContinuations.remove(continuationId);
        if (retained != null) {
            retained.close();
        }
        return retained;
    }

    private void removeProcessLocalContinuation(String continuationId, ProcessLocalContinuation expected) {
        if (processLocalContinuations.remove(continuationId, expected)) {
            expected.close();
        }
    }

    private CompletionStage<AgentResponse<Void>> runIteration(LoopState state, List<Message> messages) {
        return inner.runWithinLeaseAsync(state.lease, messages, state.runOptions, state.cancellation)
                .thenCompose(result -> {
                    if (result.continuation().isPresent()) {
                        return CompletableFuture.failedFuture(new ApprovalRequiredException(
                                result.continuation().orElseThrow(), result.rejectedDecisions()));
                    }
                    AgentResponse<Void> response = result.response().orElseThrow();
                    state.iteration++;
                    state.responses.add(response);
                    state.transcript.addAll(response.messages());
                    String progressEntry = response.text().strip();
                    if (!progressEntry.isEmpty()) {
                        state.progress.add(progressEntry);
                    }
                    if (state.iteration >= state.maxIterations || evaluators.isEmpty()) {
                        return CompletableFuture.completedFuture(state.finish(options));
                    }
                    LoopContext context = new LoopContext(
                            inner,
                            state.session,
                            state.initialMessages,
                            response,
                            state.runOptions,
                            state.iteration,
                            state.progress,
                            state.feedback,
                            state.attributes);
                    return evaluate(context, state.cancellation).thenCompose(evaluation -> {
                        if (!evaluation.shouldContinue()) {
                            return CompletableFuture.completedFuture(state.finish(options));
                        }
                        if (evaluation.feedback() != null) {
                            state.feedback.add(evaluation.feedback());
                        }
                        List<Message> next = nextMessages(evaluation);
                        state.transcript.addAll(next);
                        if (options.freshContextPerIteration()) {
                            restoreFreshContext(state);
                            next = freshMessages(state, next);
                        }
                        return runIteration(state, next);
                    });
                });
    }

    private CompletionStage<LoopEvaluation> evaluate(LoopContext context, RunCancellation cancellation) {
        CompletionStage<LoopEvaluation> stage = CompletableFuture.completedFuture(LoopEvaluation.stop());
        for (LoopEvaluator evaluator : evaluators) {
            stage = stage.thenCompose(current -> {
                if (current.shouldContinue()) {
                    return CompletableFuture.completedFuture(current);
                }
                CompletionStage<LoopEvaluation> evaluated = evaluator.evaluateAsync(context, cancellation);
                if (evaluated == null) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("LoopEvaluator returned a null stage."));
                }
                return evaluated.thenApply(value -> Objects.requireNonNull(value, "LoopEvaluator returned null."));
            });
        }
        return stage;
    }

    private void streamIteration(
            LoopState state,
            List<Message> messages,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            AtomicReference<Flow.Subscription> upstream,
            CompletableFuture<AgentResponse<Void>> terminal,
            AtomicBoolean finished) {
        if (state.cancellation.isCancellationRequested()) {
            failStream(state, new RunCancelledException(), sink, terminal, upstream, finished);
            return;
        }
        ArrayList<AgentResponseUpdate> updates = new ArrayList<>();
        try {
            ChatAgent.SessionStreamingExecution execution =
                    inner.runStreamingExecutionWithinLease(state.lease, messages, state.runOptions, state.cancellation);
            synchronized (state.streamMonitor) {
                if (finished.get()) {
                    return;
                }
                state.activeStreamingCleanup.set(execution.settledAsync());
            }
            execution.updates().subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription nextSubscription) {
                    subscription = nextSubscription;
                    Flow.Subscription previous = upstream.getAndSet(nextSubscription);
                    if (previous != null) {
                        previous.cancel();
                    }
                    nextSubscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AgentResponseUpdate update) {
                    if (finished.get()) {
                        return;
                    }
                    updates.add(update);
                    try {
                        sink.emit(update);
                    } catch (RuntimeException failure) {
                        subscription.cancel();
                        failStream(state, failure, sink, terminal, upstream, finished);
                    }
                }

                @Override
                public void onError(Throwable failure) {
                    upstream.compareAndSet(subscription, null);
                    failStream(state, RunHandles.unwrap(failure), sink, terminal, upstream, finished);
                }

                @Override
                public void onComplete() {
                    upstream.compareAndSet(subscription, null);
                    completeStreamingIteration(state, updates, sink, upstream, terminal, finished);
                }
            });
        } catch (RuntimeException failure) {
            failStream(state, failure, sink, terminal, upstream, finished);
        }
    }

    private void completeStreamingIteration(
            LoopState state,
            List<AgentResponseUpdate> updates,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            AtomicReference<Flow.Subscription> upstream,
            CompletableFuture<AgentResponse<Void>> terminal,
            AtomicBoolean finished) {
        AgentResponse<Void> response;
        try {
            response = aggregateIteration(updates);
        } catch (RuntimeException failure) {
            failStream(state, failure, sink, terminal, upstream, finished);
            return;
        }

        LoopContext context;
        synchronized (state.streamMonitor) {
            if (finished.get()) {
                return;
            }
            state.iteration++;
            state.responses.add(response);
            state.transcript.addAll(response.messages());
            String progressEntry = response.text().strip();
            if (!progressEntry.isEmpty()) {
                state.progress.add(progressEntry);
            }
            if (state.iteration >= state.maxIterations || evaluators.isEmpty()) {
                completeStream(state, sink, terminal, finished);
                return;
            }
            context = new LoopContext(
                    inner,
                    state.session,
                    state.initialMessages,
                    response,
                    state.runOptions,
                    state.iteration,
                    state.progress,
                    state.feedback,
                    state.attributes);
        }

        if (finished.get()) {
            return;
        }
        CompletionStage<LoopEvaluation> evaluation;
        try {
            evaluation = evaluate(context, state.cancellation);
        } catch (RuntimeException failure) {
            failStream(state, failure, sink, terminal, upstream, finished);
            return;
        }
        evaluation.whenComplete((value, failure) -> {
            if (failure != null) {
                failStream(state, RunHandles.unwrap(failure), sink, terminal, upstream, finished);
                return;
            }
            List<Message> next;
            synchronized (state.streamMonitor) {
                if (finished.get()) {
                    return;
                }
                if (!value.shouldContinue()) {
                    completeStream(state, sink, terminal, finished);
                    return;
                }
                if (value.feedback() != null) {
                    state.feedback.add(value.feedback());
                }
                next = nextMessages(value);
                state.transcript.addAll(next);
                if (options.freshContextPerIteration()) {
                    restoreFreshContext(state);
                    next = freshMessages(state, next);
                }
            }
            try {
                for (Message message : next) {
                    if (finished.get()) {
                        return;
                    }
                    sink.emit(toUpdate(message));
                }
                if (finished.get()) {
                    return;
                }
                streamIteration(state, next, sink, upstream, terminal, finished);
            } catch (RuntimeException nextFailure) {
                failStream(state, nextFailure, sink, terminal, upstream, finished);
            }
        });
    }

    private AgentResponse<Void> aggregateIteration(List<AgentResponseUpdate> updates) {
        if (updates.isEmpty()) {
            return AgentResponse.<Void>builder().agentId(inner.metadata().id()).build();
        }

        ArrayList<AgentResponse<Void>> segments = new ArrayList<>();
        ResponseAggregator.AgentAggregation<Void> aggregation = ResponseAggregator.agent();
        for (AgentResponseUpdate update : updates) {
            if (aggregation.isTerminal()) {
                segments.add(aggregation.response());
                aggregation = ResponseAggregator.agent();
            }
            try {
                aggregation.add(update);
            } catch (ValidationException incompatibleSegment) {
                segments.add(aggregation.finish());
                aggregation = ResponseAggregator.agent();
                aggregation.add(update);
            }
        }
        segments.add(aggregation.isTerminal() ? aggregation.response() : aggregation.finish());

        AgentResponse<Void> last = segments.getLast();
        ArrayList<Message> messages = new ArrayList<>();
        ArrayList<Long> sequences = new ArrayList<>();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        UsageDetails usage = null;
        for (AgentResponse<Void> segment : segments) {
            messages.addAll(segment.messages());
            sequences.addAll(segment.updateSequences());
            metadata.putAll(segment.metadata());
            if (segment.usage() != null) {
                usage = usage == null ? segment.usage() : usage.fold(segment.usage());
            }
        }
        return AgentResponse.<Void>builder()
                .messages(messages)
                .responseId(last.responseId())
                .agentId(last.agentId() == null ? inner.metadata().id() : last.agentId())
                .createdAt(last.createdAt())
                .finishReason(last.finishReason())
                .usage(usage)
                .continuationToken(last.continuationToken())
                .metadata(metadata)
                .updateSequences(sequences)
                .build();
    }

    private static AgentResponseUpdate toUpdate(Message message) {
        AgentResponseUpdate.Builder builder = AgentResponseUpdate.builder()
                .contents(message.contents())
                .role(message.role())
                .metadata(message.metadata());
        if (message.authorName() != null) {
            builder.authorName(message.authorName());
        }
        if (message.messageId() != null) {
            builder.messageId(message.messageId());
        }
        return builder.build();
    }

    private void completeStream(
            LoopState state,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<AgentResponse<Void>> terminal,
            AtomicBoolean finished) {
        synchronized (state.streamMonitor) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                terminal.complete(state.finish(options));
                sink.complete();
            } catch (RuntimeException failure) {
                terminal.completeExceptionally(failure);
                sink.fail(failure);
            }
        }
    }

    private static void failStream(
            LoopState state,
            Throwable failure,
            SingleSubscriberPublisher<AgentResponseUpdate> sink,
            CompletableFuture<AgentResponse<Void>> terminal,
            AtomicReference<Flow.Subscription> upstream,
            AtomicBoolean finished) {
        CompletionStage<Void> cleanup;
        Flow.Subscription subscription;
        synchronized (state.streamMonitor) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            subscription = upstream.getAndSet(null);
            cleanup = state.activeStreamingCleanup.get();
        }
        if (subscription != null) {
            subscription.cancel();
        }
        cleanup.whenComplete((ignored, cleanupFailure) -> {
            synchronized (state.streamMonitor) {
                terminal.completeExceptionally(failure);
                sink.fail(failure);
            }
        });
    }

    private static void restoreFreshContext(LoopState state) {
        state.session.restoreSnapshotPreservingState(
                state.initialSnapshot, key -> key.startsWith(BackgroundAgentsProvider.STATE_PREFIX));
    }

    private List<Message> nextMessages(LoopEvaluation evaluation) {
        if (!evaluation.messages().isEmpty()) {
            return evaluation.messages();
        }
        String text = evaluation.feedback() == null ? options.defaultNextMessage() : evaluation.feedback();
        return List.of(Message.builder(Role.USER)
                .contents(List.of(new com.microsoft.agents.core.TextContent(text)))
                .authorName(options.progressAuthorName())
                .build());
    }

    private List<Message> freshMessages(LoopState state, List<Message> next) {
        ArrayList<Message> messages = new ArrayList<>(state.initialMessages);
        if (!state.progress.isEmpty()) {
            messages.add(Message.builder(Role.USER)
                    .contents(List.of(new com.microsoft.agents.core.TextContent(
                            "Progress from prior iterations:\n- " + String.join("\n- ", state.progress))))
                    .authorName(options.progressAuthorName())
                    .build());
        }
        messages.addAll(next);
        return List.copyOf(messages);
    }

    private int effectiveMaxIterations(RunOptions runOptions) {
        return runOptions.maxIterations() == null
                ? options.maxIterations()
                : Math.min(options.maxIterations(), runOptions.maxIterations());
    }

    private static CompletionStage<AgentRunResult<Void>> toRunResult(CompletionStage<AgentResponse<Void>> response) {
        CompletableFuture<AgentRunResult<Void>> result = new CompletableFuture<>();
        response.whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(AgentRunResult.completed(value, List.of()));
                return;
            }
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof ApprovalRequiredException approval) {
                result.complete(AgentRunResult.inputRequired(approval.continuation(), approval.rejectedDecisions()));
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private static final class LoopState {
        private final AgentSession session;

        private final AgentSession.RunLease lease;

        private final AgentSessionSnapshot initialSnapshot;

        private final List<Message> initialMessages;

        private final RunOptions runOptions;

        private final RunCancellation cancellation;

        private final int maxIterations;

        private final ArrayList<AgentResponse<Void>> responses = new ArrayList<>();

        private final ArrayList<Message> transcript = new ArrayList<>();

        private final ArrayList<String> progress = new ArrayList<>();

        private final ArrayList<String> feedback = new ArrayList<>();

        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        private final Object streamMonitor = new Object();

        private final AtomicReference<CompletionStage<Void>> activeStreamingCleanup =
                new AtomicReference<>(CompletableFuture.completedFuture(null));

        private int iteration;

        private LoopState(
                AgentSession session,
                AgentSession.RunLease lease,
                AgentSessionSnapshot initialSnapshot,
                List<Message> initialMessages,
                RunOptions runOptions,
                RunCancellation cancellation,
                int maxIterations) {
            this.session = session;
            this.lease = lease;
            this.initialSnapshot = initialSnapshot;
            this.initialMessages = List.copyOf(initialMessages);
            this.runOptions = runOptions;
            this.cancellation = cancellation;
            this.maxIterations = maxIterations;
        }

        private AgentResponse<Void> finish(LoopAgentOptions options) {
            AgentResponse<Void> last = responses.getLast();
            if (options.returnFinalOnly()) {
                return last;
            }
            return AgentResponse.<Void>builder()
                    .messages(transcript)
                    .responseId(last.responseId())
                    .agentId(last.agentId())
                    .createdAt(last.createdAt())
                    .finishReason(last.finishReason())
                    .usage(last.usage())
                    .continuationToken(last.continuationToken())
                    .metadata(last.metadata())
                    .updateSequences(last.updateSequences())
                    .build();
        }
    }

    private static final class ProcessLocalContinuation implements AutoCloseable {
        private final AgentSession session;

        private RunCancellationRegistration registration;

        private boolean closed;

        private ProcessLocalContinuation(AgentSession session) {
            this.session = Objects.requireNonNull(session, "session");
        }

        private AgentSession session() {
            return session;
        }

        private synchronized void attach(RunCancellationRegistration nextRegistration) {
            RunCancellationRegistration safeRegistration = Objects.requireNonNull(nextRegistration, "registration");
            if (closed) {
                safeRegistration.close();
                return;
            }
            if (registration != null) {
                throw new IllegalStateException("Cancellation registration is already attached.");
            }
            registration = safeRegistration;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }
    }
}
