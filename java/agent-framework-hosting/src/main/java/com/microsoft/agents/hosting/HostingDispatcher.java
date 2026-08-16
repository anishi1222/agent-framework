// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentRunResult;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.SessionBusyException;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.StorageConflictException;
import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.orchestrations.OrchestrationContinuation;
import com.microsoft.agents.orchestrations.OrchestrationContinuationKind;
import com.microsoft.agents.orchestrations.OrchestrationEvent;
import com.microsoft.agents.orchestrations.OrchestrationOutcome;
import com.microsoft.agents.orchestrations.OrchestrationResult;
import com.microsoft.agents.tools.ToolApprovalDecision;
import com.microsoft.agents.tools.ToolApprovalRequest;
import com.microsoft.agents.workflows.WorkflowEvent;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatches authenticated, authorized finite and streaming runs to registered agents and workflows.
 *
 * <p>Every active run is bound to principal, isolation, route, and host-generated run identity.
 * Approval continuations are opaque, one-time, process-local, capacity bounded, and expiring. The
 * dispatcher never restarts completed work and does not claim cross-process continuation support.
 */
public final class HostingDispatcher implements AutoCloseable {
    private final HostingRegistry registry;

    private final HostingLimits limits;

    private final HostingAuthorizer authorizer;

    private final ActiveHostingRunRegistry activeRuns;

    private final HostingContinuationRegistry continuations;

    private final ScheduledExecutorService scheduler;

    private final Clock clock;

    private final AtomicBoolean closed = new AtomicBoolean();

    private final Set<AuthorizationAdmission> pendingAuthorizations = ConcurrentHashMap.newKeySet();

    /**
     * Creates a dispatcher using authenticated-allow authorization.
     *
     * @param registry target registry
     * @param limits mandatory limits
     */
    public HostingDispatcher(HostingRegistry registry, HostingLimits limits) {
        this(registry, limits, HostingAuthorizer.allowAuthenticated());
    }

    /**
     * Creates a dispatcher with explicit route authorization.
     *
     * @param registry target registry
     * @param limits mandatory limits
     * @param authorizer route authorization policy
     */
    public HostingDispatcher(HostingRegistry registry, HostingLimits limits, HostingAuthorizer authorizer) {
        this(registry, limits, authorizer, Clock.systemUTC(), newDeadlineScheduler());
    }

    HostingDispatcher(
            HostingRegistry registry,
            HostingLimits limits,
            HostingAuthorizer authorizer,
            Clock clock,
            ScheduledExecutorService scheduler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        activeRuns = new ActiveHostingRunRegistry(limits.maxConcurrentRuns());
        continuations = new HostingContinuationRegistry(limits);
    }

    /**
     * Lists authorized route descriptors.
     *
     * @param context trusted request context
     * @param kind route kind
     * @return descriptor stage
     */
    public CompletionStage<List<HostingRouteDescriptor>> listAsync(
            HostingRequestContext context, HostingRouteKind kind) {
        requireOpen();
        Objects.requireNonNull(kind, "kind");
        return authorize(context, null, HostingAuthorizationAction.DISCOVER).thenApply(ignored -> switch (kind) {
            case AGENT -> registry.agents();
            case WORKFLOW -> registry.workflows();
            case ORCHESTRATION -> registry.orchestrations();
        });
    }

    /**
     * Gets one authorized route descriptor.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @return descriptor stage
     */
    public CompletionStage<HostingRouteDescriptor> descriptorAsync(
            HostingRequestContext context, HostingRouteKind kind, String routeId) {
        requireOpen();
        HostingRouteDescriptor descriptor =
                registry.requireRegistration(kind, routeId).descriptor();
        return authorize(context, descriptor, HostingAuthorizationAction.DISCOVER)
                .thenApply(ignored -> descriptor);
    }

    /**
     * Starts one finite hosted execution.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @param request run request
     * @return terminal outcome stage
     */
    public CompletionStage<HostingOutcome> runAsync(
            HostingRequestContext context, HostingRouteKind kind, String routeId, HostingRunRequest request) {
        requireOpen();
        HostingRegistry.Registration registration = registry.requireRegistration(kind, routeId);
        return authorize(context, registration.descriptor(), HostingAuthorizationAction.START)
                .thenCompose(ignored -> startFinite(context, registration, Objects.requireNonNull(request, "request")));
    }

    /**
     * Starts one streaming hosted execution.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @param request run request
     * @return streaming run stage
     */
    public CompletionStage<HostingRun> startStreamingAsync(
            HostingRequestContext context, HostingRouteKind kind, String routeId, HostingRunRequest request) {
        requireOpen();
        HostingRegistry.Registration registration = registry.requireRegistration(kind, routeId);
        return authorize(context, registration.descriptor(), HostingAuthorizationAction.START)
                .thenApply(
                        ignored -> startStreaming(context, registration, Objects.requireNonNull(request, "request")));
    }

    /**
     * Consumes one finite process-local continuation.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @param runId owning run identifier
     * @param request resume request
     * @return terminal outcome stage
     */
    public CompletionStage<HostingOutcome> resumeAsync(
            HostingRequestContext context,
            HostingRouteKind kind,
            String routeId,
            String runId,
            HostingResumeRequest request) {
        requireOpen();
        HostingRegistry.Registration registration = registry.requireRegistration(kind, routeId);
        if (!registration.descriptor().resumeSupported()) {
            return CompletableFuture.failedFuture(new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "This route has no production continuation capability."));
        }
        return authorize(context, registration.descriptor(), HostingAuthorizationAction.RESUME)
                .thenCompose(
                        ignored -> startResume(context, registration, runId, Objects.requireNonNull(request, "request"))
                                .terminal());
    }

    /**
     * Consumes a continuation over the streaming transport.
     *
     * <p>The current process-local chat-agent approval API resumes finitely, so this publisher emits
     * no incremental events and carries the resumed outcome in its terminal envelope.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @param runId owning run identifier
     * @param request resume request
     * @return streaming run stage
     */
    public CompletionStage<HostingRun> resumeStreamingAsync(
            HostingRequestContext context,
            HostingRouteKind kind,
            String routeId,
            String runId,
            HostingResumeRequest request) {
        requireOpen();
        HostingRegistry.Registration registration = registry.requireRegistration(kind, routeId);
        if (!registration.descriptor().resumeSupported()) {
            return CompletableFuture.failedFuture(new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "This route has no production continuation capability."));
        }
        return authorize(context, registration.descriptor(), HostingAuthorizationAction.RESUME)
                .thenApply(ignored -> {
                    ResumePhase phase =
                            startResume(context, registration, runId, Objects.requireNonNull(request, "request"));
                    return new HostingRun(
                            runId,
                            new TerminalOutcomePublisher(phase.terminal(), phase.cancellation()::cancel),
                            phase.terminal(),
                            phase.cancellation());
                });
    }

    /**
     * Cancels one active run after principal, isolation, route, and run validation.
     *
     * @param context trusted request context
     * @param kind route kind
     * @param routeId route identifier
     * @param runId active run identifier
     * @return stage yielding whether this call initiated cancellation
     */
    public CompletionStage<Boolean> cancelAsync(
            HostingRequestContext context, HostingRouteKind kind, String routeId, String runId) {
        requireOpen();
        HostingRouteDescriptor descriptor =
                registry.requireRegistration(kind, routeId).descriptor();
        return authorize(context, descriptor, HostingAuthorizationAction.CANCEL)
                .thenApply(ignored -> activeRuns.cancel(context, kind, routeId, runId));
    }

    /**
     * Returns the current active-run count.
     *
     * @return active runs
     */
    public int activeRunCount() {
        return activeRuns.size();
    }

    /**
     * Returns the current unconsumed process-local continuation count.
     *
     * @return continuations
     */
    public int continuationCount() {
        return continuations.availableCount();
    }

    /**
     * Discards a continuation from an outcome that a transport could not deliver.
     *
     * <p>Transport adapters call this only after outcome encoding or delivery fails. Outcomes without
     * a continuation are ignored.
     *
     * @param outcome undelivered outcome
     */
    public void discardUndeliveredOutcome(HostingOutcome outcome) {
        discardOutcome(Objects.requireNonNull(outcome, "outcome"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List.copyOf(pendingAuthorizations).forEach(AuthorizationAdmission::closeDispatcher);
        activeRuns.close();
        continuations.close();
        scheduler.shutdownNow();
    }

    private CompletionStage<HostingOutcome> startFinite(
            HostingRequestContext context, HostingRegistry.Registration registration, HostingRunRequest request) {
        String runId = newRunId();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        ActiveHostingRunRegistry.Entry active;
        try {
            active = activeRuns.register(
                    context,
                    registration.descriptor().kind(),
                    registration.descriptor().id(),
                    runId,
                    cancellation);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<HostingOutcome> terminal = new CompletableFuture<>();
        installLifecycle(active, cancellation, terminal);
        try {
            switch (registration) {
                case HostingRegistry.AgentRegistration agent ->
                    startAgentFinite(context, agent, request, active, terminal);
                case HostingRegistry.WorkflowRegistration<?, ?> workflow ->
                    startWorkflowFinite(context, workflow, request, active, terminal);
                case HostingRegistry.OrchestrationRegistration<?> orchestration ->
                    startOrchestrationFinite(context, orchestration, request, active, terminal);
            }
        } catch (RuntimeException failure) {
            terminal.complete(failedOutcome(runId, failure, active));
        }
        return terminal.minimalCompletionStage();
    }

    private HostingRun startStreaming(
            HostingRequestContext context, HostingRegistry.Registration registration, HostingRunRequest request) {
        String runId = newRunId();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        ActiveHostingRunRegistry.Entry active = activeRuns.register(
                context,
                registration.descriptor().kind(),
                registration.descriptor().id(),
                runId,
                cancellation);
        CompletableFuture<HostingOutcome> terminal = new CompletableFuture<>();
        AtomicBoolean overflowPending = new AtomicBoolean();
        installLifecycle(active, cancellation, terminal, overflowPending);
        AtomicLong sequence = new AtomicLong();
        try {
            Flow.Publisher<HostingEvent> publisher =
                    switch (registration) {
                        case HostingRegistry.AgentRegistration agent -> {
                            if (request.messages().isEmpty()) {
                                throw new HostingException(
                                        HostingErrorCode.UNPROCESSABLE, "Agent route requires at least one message.");
                            }
                            Flow.Publisher<AgentResponseUpdate> source = agent.agent()
                                    .runStreaming(request.messages(), mergedOptions(context, request), cancellation);
                            yield new HostedStreamingPublisher<>(
                                    source,
                                    limits.maxSseBufferedEvents(),
                                    update -> event(
                                            sequence,
                                            HostingEventType.AGENT_UPDATE,
                                            runId,
                                            HostingWireValues.agentUpdateValue(update)),
                                    cancellation::cancel,
                                    failure -> streamingFailure(context, agent, runId, failure, active),
                                    () -> HostingOutcome.completed(runId, StateValue.object(Map.of())),
                                    this::discardOutcome,
                                    overflowPending,
                                    terminal);
                        }
                        case HostingRegistry.WorkflowRegistration<?, ?> workflow ->
                            workflowStreaming(
                                    context,
                                    workflow,
                                    request,
                                    runId,
                                    cancellation,
                                    active,
                                    sequence,
                                    overflowPending,
                                    terminal);
                        case HostingRegistry.OrchestrationRegistration<?> orchestration ->
                            orchestrationStreaming(
                                    context,
                                    orchestration,
                                    request,
                                    runId,
                                    cancellation,
                                    active,
                                    sequence,
                                    overflowPending,
                                    terminal);
                    };
            return new HostingRun(runId, publisher, terminal.minimalCompletionStage(), cancellation);
        } catch (RuntimeException failure) {
            terminal.complete(failedOutcome(runId, failure, active));
            return new HostingRun(
                    runId,
                    new TerminalOutcomePublisher(terminal, cancellation::cancel),
                    terminal.minimalCompletionStage(),
                    cancellation);
        }
    }

    private void startAgentFinite(
            HostingRequestContext context,
            HostingRegistry.AgentRegistration registration,
            HostingRunRequest request,
            ActiveHostingRunRegistry.Entry active,
            CompletableFuture<HostingOutcome> terminal) {
        if (request.messages().isEmpty()) {
            throw new HostingException(HostingErrorCode.UNPROCESSABLE, "Agent route requires at least one message.");
        }
        RunHandle<? extends AgentResponse<?>> handle = registration
                .agent()
                .startRun(request.messages(), mergedOptions(context, request), activeCancellation(active));
        handle.resultAsync().whenComplete((response, failure) -> {
            try {
                if (failure == null) {
                    completeOutcome(
                            terminal,
                            HostingOutcome.completed(active.runId(), HostingWireValues.agentResponseValue(response)));
                } else {
                    completeOutcome(
                            terminal, finiteAgentFailure(context, registration, active.runId(), failure, active));
                }
            } catch (RuntimeException callbackFailure) {
                completeOutcome(terminal, failedOutcome(active.runId(), callbackFailure, active));
            }
        });
    }

    private <I, O> void startWorkflowFinite(
            HostingRequestContext context,
            HostingRegistry.WorkflowRegistration<I, O> registration,
            HostingRunRequest request,
            ActiveHostingRunRegistry.Entry active,
            CompletableFuture<HostingOutcome> terminal) {
        RunHandle<WorkflowRunResult<O>> handle =
                registration.start(mergedWorkflowRequest(context, request), active.runId(), activeCancellation(active));
        handle.resultAsync().whenComplete((result, failure) -> {
            try {
                if (failure == null) {
                    completeOutcome(
                            terminal,
                            HostingOutcome.completed(
                                    active.runId(),
                                    HostingWireValues.workflowResultValue(
                                            result, registration.encodeOutput(result.output()))));
                } else {
                    completeOutcome(terminal, failedOutcome(active.runId(), failure, active));
                }
            } catch (RuntimeException callbackFailure) {
                completeOutcome(terminal, failedOutcome(active.runId(), callbackFailure, active));
            }
        });
    }

    private <O> void startOrchestrationFinite(
            HostingRequestContext context,
            HostingRegistry.OrchestrationRegistration<O> registration,
            HostingRunRequest request,
            ActiveHostingRunRegistry.Entry active,
            CompletableFuture<HostingOutcome> terminal) {
        if (request.messages().isEmpty()) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Orchestration route requires at least one message.");
        }
        RunHandle<OrchestrationResult<O>> handle =
                registration.start(mergedWorkflowRequest(context, request), active.runId(), activeCancellation(active));
        handle.resultAsync().whenComplete((result, failure) -> {
            try {
                if (failure == null) {
                    completeOutcome(terminal, orchestrationOutcome(context, registration, active.runId(), result));
                } else {
                    completeOutcome(terminal, failedOutcome(active.runId(), failure, active));
                }
            } catch (RuntimeException callbackFailure) {
                completeOutcome(terminal, failedOutcome(active.runId(), callbackFailure, active));
            }
        });
    }

    private <I, O> Flow.Publisher<HostingEvent> workflowStreaming(
            HostingRequestContext context,
            HostingRegistry.WorkflowRegistration<I, O> registration,
            HostingRunRequest request,
            String runId,
            RunCancellation cancellation,
            ActiveHostingRunRegistry.Entry active,
            AtomicLong sequence,
            AtomicBoolean overflowPending,
            CompletableFuture<HostingOutcome> terminal) {
        Flow.Publisher<WorkflowEvent> source =
                registration.stream(mergedWorkflowRequest(context, request), runId, cancellation);
        return new HostedStreamingPublisher<>(
                source,
                limits.maxSseBufferedEvents(),
                update -> event(
                        sequence, HostingEventType.WORKFLOW_EVENT, runId, HostingWireValues.workflowEventValue(update)),
                cancellation::cancel,
                failure -> failedOutcome(runId, failure, active),
                () -> HostingOutcome.completed(runId, StateValue.object(Map.of())),
                this::discardOutcome,
                overflowPending,
                terminal);
    }

    private <O> Flow.Publisher<HostingEvent> orchestrationStreaming(
            HostingRequestContext context,
            HostingRegistry.OrchestrationRegistration<O> registration,
            HostingRunRequest request,
            String runId,
            RunCancellation cancellation,
            ActiveHostingRunRegistry.Entry active,
            AtomicLong sequence,
            AtomicBoolean overflowPending,
            CompletableFuture<HostingOutcome> terminal) {
        if (request.messages().isEmpty()) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Orchestration route requires at least one message.");
        }
        AtomicReference<OrchestrationResult<O>> result = new AtomicReference<>();
        Flow.Publisher<OrchestrationEvent> source = subscriber -> {
            SubmissionPublisher<OrchestrationEvent> publisher =
                    new SubmissionPublisher<>(Runnable::run, limits.maxSseBufferedEvents());
            publisher.subscribe(subscriber);
            RunHandle<OrchestrationResult<O>> handle;
            try {
                handle = registration.start(
                        mergedWorkflowRequest(context, request), runId, cancellation, publisher::submit);
            } catch (RuntimeException failure) {
                publisher.closeExceptionally(failure);
                return;
            }
            handle.resultAsync().whenComplete((completed, failure) -> {
                if (failure == null) {
                    result.set(completed);
                    publisher.close();
                } else {
                    publisher.closeExceptionally(RunHandles.unwrap(failure));
                }
            });
        };
        return new HostedStreamingPublisher<>(
                source,
                limits.maxSseBufferedEvents(),
                update -> event(
                        sequence,
                        HostingEventType.ORCHESTRATION_EVENT,
                        runId,
                        HostingWireValues.orchestrationEventValue(update)),
                cancellation::cancel,
                failure -> failedOutcome(runId, failure, active),
                () -> orchestrationOutcome(
                        context, registration, runId, Objects.requireNonNull(result.get(), "orchestration result")),
                this::discardOutcome,
                overflowPending,
                terminal);
    }

    private <O> HostingOutcome orchestrationOutcome(
            HostingRequestContext context,
            HostingRegistry.OrchestrationRegistration<O> registration,
            String runId,
            OrchestrationResult<O> result) {
        if (result.outcome() == OrchestrationOutcome.INPUT_REQUIRED) {
            return orchestrationContinuationOutcome(context, registration, runId, result.continuation());
        }
        if (result.outcome() == OrchestrationOutcome.FAILED) {
            return HostingOutcome.failed(
                    runId,
                    HostingError.of(
                            HostingErrorCode.INTERNAL_ERROR, "Hosted orchestration returned a failed domain outcome."));
        }
        StateValue encodedOutput =
                result.output() == null ? StateValue.nullValue() : registration.encodeOutput(result.output());
        return HostingOutcome.completed(runId, HostingWireValues.orchestrationResultValue(result, encodedOutput));
    }

    private <O> HostingOutcome orchestrationContinuationOutcome(
            HostingRequestContext context,
            HostingRegistry.OrchestrationRegistration<O> registration,
            String runId,
            OrchestrationContinuation continuation) {
        HostingContinuationType type = continuation.kind() == OrchestrationContinuationKind.APPROVAL
                ? HostingContinuationType.APPROVAL
                : HostingContinuationType.INPUT;
        List<HostingApprovalRequest> approvalRequests = continuation.agentContinuation() == null
                ? List.of()
                : HostingWireValues.approvalRequests(
                        continuation.agentContinuation().approvalRequests());
        HostingContinuationDescriptor issued = continuations.issue(
                new HostingContinuationRegistry.Binding(
                        context.principalId(),
                        context.isolationId(),
                        registration.descriptor().kind(),
                        registration.descriptor().id(),
                        runId,
                        type),
                new OrchestrationContinuationState(registration, continuation),
                approvalRequests,
                () -> {});
        if (type == HostingContinuationType.APPROVAL) {
            return HostingOutcome.approvalRequired(runId, issued);
        }
        return new HostingOutcome(HostingOutcomeStatus.INPUT_REQUIRED, runId, null, issued, null);
    }

    private ResumePhase startResume(
            HostingRequestContext context,
            HostingRegistry.Registration registration,
            String runId,
            HostingResumeRequest request) {
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        ActiveHostingRunRegistry.Entry active = activeRuns.register(
                context,
                registration.descriptor().kind(),
                registration.descriptor().id(),
                runId,
                cancellation);
        CompletableFuture<HostingOutcome> terminal = new CompletableFuture<>();
        installLifecycle(active, cancellation, terminal);
        try {
            Object payload = continuations.consume(
                    context,
                    registration.descriptor().kind(),
                    registration.descriptor().id(),
                    runId,
                    request.type(),
                    request.token());
            if (payload instanceof AgentApprovalState approval
                    && registration instanceof HostingRegistry.AgentRegistration) {
                List<ToolApprovalDecision> decisions =
                        decisions(approval.continuation().approvalRequests(), request.decisions());
                approval.agent()
                        .resumeAsync(approval.continuation(), decisions, cancellation)
                        .whenComplete((result, failure) -> {
                            try {
                                if (failure != null) {
                                    completeOutcome(terminal, failedOutcome(runId, failure, active));
                                    return;
                                }
                                completeOutcome(
                                        terminal,
                                        resumedOutcome(
                                                context, registration.descriptor(), runId, approval.agent(), result));
                            } catch (RuntimeException callbackFailure) {
                                completeOutcome(terminal, failedOutcome(runId, callbackFailure, active));
                            }
                        });
            } else if (payload instanceof OrchestrationContinuationState orchestration
                    && registration == orchestration.registration()) {
                resumeOrchestration(context, orchestration, request, runId, cancellation, active, terminal);
            } else {
                throw new HostingException(
                        HostingErrorCode.CONFLICT, "Continuation payload does not match this route.");
            }
        } catch (RuntimeException failure) {
            terminal.complete(failedOutcome(runId, failure, active));
        }
        return new ResumePhase(terminal.minimalCompletionStage(), cancellation);
    }

    @SuppressWarnings("unchecked")
    private void resumeOrchestration(
            HostingRequestContext context,
            OrchestrationContinuationState state,
            HostingResumeRequest request,
            String runId,
            RunCancellation cancellation,
            ActiveHostingRunRegistry.Entry active,
            CompletableFuture<HostingOutcome> terminal) {
        HostingRegistry.OrchestrationRegistration<Object> registration =
                (HostingRegistry.OrchestrationRegistration<Object>) state.registration();
        registration
                .resume(state.continuation(), request, runId, cancellation)
                .resultAsync()
                .whenComplete((result, failure) -> {
                    try {
                        if (failure != null) {
                            completeOutcome(terminal, failedOutcome(runId, failure, active));
                            return;
                        }
                        completeOutcome(terminal, orchestrationOutcome(context, registration, runId, result));
                    } catch (RuntimeException callbackFailure) {
                        completeOutcome(terminal, failedOutcome(runId, callbackFailure, active));
                    }
                });
    }

    private HostingOutcome resumedOutcome(
            HostingRequestContext context,
            HostingRouteDescriptor descriptor,
            String runId,
            ChatAgent agent,
            AgentRunResult<Void> result) {
        return result.response()
                .<HostingOutcome>map(
                        response -> HostingOutcome.completed(runId, HostingWireValues.agentResponseValue(response)))
                .orElseGet(() -> approvalOutcome(
                        context, descriptor, runId, agent, result.continuation().orElseThrow()));
    }

    private HostingOutcome finiteAgentFailure(
            HostingRequestContext context,
            HostingRegistry.AgentRegistration registration,
            String runId,
            Throwable failure,
            ActiveHostingRunRegistry.Entry active) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof ApprovalRequiredException approval
                && registration.agent() instanceof ChatAgent chatAgent) {
            return approvalOutcome(context, registration.descriptor(), runId, chatAgent, approval.continuation());
        }
        return failedOutcome(runId, cause, active);
    }

    private HostingOutcome streamingFailure(
            HostingRequestContext context,
            HostingRegistry.AgentRegistration registration,
            String runId,
            Throwable failure,
            ActiveHostingRunRegistry.Entry active) {
        return finiteAgentFailure(context, registration, runId, failure, active);
    }

    private HostingOutcome approvalOutcome(
            HostingRequestContext context,
            HostingRouteDescriptor descriptor,
            String runId,
            ChatAgent agent,
            AgentContinuation continuation) {
        HostingContinuationDescriptor issued;
        try {
            issued = continuations.issue(
                    new HostingContinuationRegistry.Binding(
                            context.principalId(),
                            context.isolationId(),
                            descriptor.kind(),
                            descriptor.id(),
                            runId,
                            HostingContinuationType.APPROVAL),
                    new AgentApprovalState(agent, continuation),
                    HostingWireValues.approvalRequests(continuation.approvalRequests()),
                    () -> agent.discardContinuation(continuation));
        } catch (RuntimeException failure) {
            agent.discardContinuation(continuation);
            throw failure;
        }
        return HostingOutcome.approvalRequired(runId, issued);
    }

    private List<ToolApprovalDecision> decisions(
            List<ToolApprovalRequest> requests, List<HostingApprovalDecision> decisions) {
        LinkedHashMap<String, ToolApprovalRequest> pending = new LinkedHashMap<>();
        requests.forEach(request -> pending.put(request.approvalId().value(), request));
        LinkedHashMap<String, HostingApprovalDecision> supplied = new LinkedHashMap<>();
        decisions.forEach(decision -> {
            if (supplied.putIfAbsent(decision.approvalId(), decision) != null) {
                throw new HostingException(
                        HostingErrorCode.UNPROCESSABLE, "Approval decision identifiers must be unique.");
            }
        });
        if (!pending.keySet().equals(supplied.keySet())) {
            throw new HostingException(
                    HostingErrorCode.UNPROCESSABLE, "Approval decisions must match every pending approval exactly.");
        }
        ArrayList<ToolApprovalDecision> result = new ArrayList<>(requests.size());
        requests.forEach(request -> {
            HostingApprovalDecision decision = supplied.get(request.approvalId().value());
            result.add(
                    decision.approved()
                            ? ToolApprovalDecision.approve(request)
                            : ToolApprovalDecision.reject(request, decision.reason()));
        });
        return List.copyOf(result);
    }

    private CompletionStage<Void> authorize(
            HostingRequestContext context, HostingRouteDescriptor descriptor, HostingAuthorizationAction action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        AuthorizationAdmission admission = new AuthorizationAdmission();
        pendingAuthorizations.add(admission);
        if (closed.get()) {
            admission.closeDispatcher();
        }
        try {
            admission.attachCancellation(RunCancellations.register(
                    context.cancellation(),
                    () -> admission.fail(new HostingException(
                            HostingErrorCode.CLIENT_CANCELLED, "Hosting authorization was cancelled."))));
            if (!admission.isTerminal()) {
                admission.attachTimeout(scheduler.schedule(
                        () -> admission.fail(new HostingException(
                                HostingErrorCode.RUN_TIMEOUT, "Hosting authorization exceeded the transport timeout.")),
                        limits.idleTimeout().toMillis(),
                        TimeUnit.MILLISECONDS));
            }
        } catch (RuntimeException failure) {
            admission.fail(
                    closed.get()
                            ? new HostingException(
                                    HostingErrorCode.CLIENT_CANCELLED,
                                    "Hosting dispatcher closed during authorization.",
                                    failure)
                            : new HostingException(
                                    HostingErrorCode.INTERNAL_ERROR,
                                    "Hosting authorization could not be scheduled.",
                                    failure));
        }
        CompletionStage<HostingAuthorizationDecision> stage;
        if (!admission.isTerminal()) {
            try {
                stage = authorizer.authorizeAsync(context, descriptor, action);
                if (stage == null) {
                    admission.fail(
                            new HostingException(HostingErrorCode.INTERNAL_ERROR, "Hosting authorization failed."));
                } else if (!admission.isTerminal()) {
                    stage.whenComplete((decision, failure) -> {
                        if (failure != null) {
                            admission.fail(RunHandles.unwrap(failure));
                        } else {
                            admission.complete(decision);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                admission.fail(new HostingException(
                        HostingErrorCode.INTERNAL_ERROR, "Hosting authorization failed.", failure));
            }
        }
        CompletableFuture<Void> authorized = new CompletableFuture<>();
        admission.result.whenComplete((decision, failure) -> {
            if (failure != null) {
                Throwable cause = RunHandles.unwrap(failure);
                authorized.completeExceptionally(
                        cause instanceof HostingException
                                ? cause
                                : new HostingException(
                                        HostingErrorCode.INTERNAL_ERROR, "Hosting authorization failed.", cause));
            } else if (decision == null || !decision.allowed()) {
                authorized.completeExceptionally(
                        new HostingException(HostingErrorCode.FORBIDDEN, "Hosting operation is forbidden."));
            } else {
                authorized.complete(null);
            }
        });
        authorized.whenComplete((ignored, failure) -> {
            if (authorized.isCancelled()) {
                admission.fail(new HostingException(
                        HostingErrorCode.CLIENT_CANCELLED, "Hosting authorization was cancelled."));
            }
        });
        return authorized;
    }

    private RunOptions mergedOptions(HostingRequestContext context, HostingRunRequest request) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.putAll(context.metadata());
        metadata.putAll(request.metadata());
        metadata.putAll(request.options().metadata());
        metadata.put("hosting.requestId", StateValue.string(context.requestId()));
        metadata.put("hosting.correlationId", StateValue.string(context.correlationId()));
        metadata.put("hosting.principalId", StateValue.string(context.principalId()));
        metadata.put("hosting.isolationId", StateValue.string(context.isolationId()));
        return new RunOptions(
                request.options().maxIterations(), request.options().maxFunctionCalls(), metadata);
    }

    private HostingRunRequest mergedWorkflowRequest(HostingRequestContext context, HostingRunRequest request) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();
        metadata.putAll(context.metadata());
        metadata.putAll(request.metadata());
        metadata.put("hosting.requestId", StateValue.string(context.requestId()));
        metadata.put("hosting.correlationId", StateValue.string(context.correlationId()));
        return new HostingRunRequest(request.messages(), request.input(), request.options(), metadata);
    }

    private void installLifecycle(
            ActiveHostingRunRegistry.Entry active,
            DefaultRunCancellation cancellation,
            CompletableFuture<HostingOutcome> terminal) {
        installLifecycle(active, cancellation, terminal, new AtomicBoolean());
    }

    private void installLifecycle(
            ActiveHostingRunRegistry.Entry active,
            DefaultRunCancellation cancellation,
            CompletableFuture<HostingOutcome> terminal,
            AtomicBoolean overflowPending) {
        RunCancellationRegistration cancellationListener = RunCancellations.register(cancellation, () -> {
            if (!overflowPending.get()) {
                terminal.complete(cancelledOutcome(active));
            }
        });
        terminal.whenComplete((ignored, failure) -> {
            cancellationListener.close();
            active.finish();
        });
        active.timeout(scheduler.schedule(
                () -> {
                    active.cancel(HostingErrorCode.RUN_TIMEOUT);
                    if (!overflowPending.get()) {
                        terminal.complete(cancelledOutcome(active));
                    }
                },
                limits.runTimeout().toMillis(),
                TimeUnit.MILLISECONDS));
    }

    private HostingOutcome cancelledOutcome(ActiveHostingRunRegistry.Entry active) {
        HostingErrorCode reason = active.cancellationReason();
        if (reason == null) {
            reason = HostingErrorCode.CLIENT_CANCELLED;
        }
        String message = reason == HostingErrorCode.RUN_TIMEOUT
                ? "Hosted run exceeded its configured deadline."
                : "Hosted run was cancelled.";
        return HostingOutcome.cancelled(
                active.runId(), new HostingError(reason, message, reason == HostingErrorCode.RUN_TIMEOUT, Map.of()));
    }

    private HostingOutcome failedOutcome(String runId, Throwable failure, ActiveHostingRunRegistry.Entry active) {
        Throwable cause = RunHandles.unwrap(failure);
        if (cause instanceof HostingException hosting && hosting.error().code() == HostingErrorCode.OVERFLOW) {
            return HostingOutcome.overflow(runId, hosting.error());
        }
        if (cause instanceof RunCancelledException || active.cancellationReason() != null) {
            return cancelledOutcome(active);
        }
        HostingError error = safeError(cause);
        return HostingOutcome.failed(runId, error);
    }

    private static HostingError safeError(Throwable failure) {
        if (failure instanceof HostingException hosting) {
            return hosting.error();
        }
        if (failure instanceof SessionBusyException || failure instanceof StorageConflictException) {
            return HostingError.of(HostingErrorCode.CONFLICT, "Hosted execution conflicts with current state.");
        }
        if (failure instanceof ValidationException || failure instanceof IllegalArgumentException) {
            return HostingError.of(HostingErrorCode.UNPROCESSABLE, "Hosted execution rejected invalid input.");
        }
        return HostingError.of(HostingErrorCode.INTERNAL_ERROR, "Hosted execution failed.");
    }

    private HostingEvent event(AtomicLong sequence, HostingEventType type, String runId, StateValue data) {
        long next = sequence.getAndIncrement();
        if (next >= limits.maxEventsPerRun()) {
            throw new HostingStreamOverflowException(limits.maxEventsPerRun());
        }
        return new HostingEvent(next, type, runId, Instant.now(clock), data);
    }

    private void completeOutcome(CompletableFuture<HostingOutcome> terminal, HostingOutcome outcome) {
        if (!terminal.complete(outcome)) {
            discardOutcome(outcome);
        }
    }

    private void discardOutcome(HostingOutcome outcome) {
        if (outcome.continuation() != null) {
            continuations.discard(outcome.continuation().token());
        }
    }

    private static RunCancellation activeCancellation(ActiveHostingRunRegistry.Entry active) {
        return active.cancellation();
    }

    private static String newRunId() {
        return "run-" + UUID.randomUUID();
    }

    int pendingAuthorizationCount() {
        return pendingAuthorizations.size();
    }

    private static ScheduledThreadPoolExecutor newDeadlineScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("agent-framework-hosting-deadlines")
                        .factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new HostingException(HostingErrorCode.CONFLICT, "Hosting dispatcher is closed.");
        }
    }

    private final class AuthorizationAdmission {
        private final CompletableFuture<HostingAuthorizationDecision> result = new CompletableFuture<>();

        private final AtomicBoolean terminal = new AtomicBoolean();

        private final AtomicReference<RunCancellationRegistration> cancellationRegistration = new AtomicReference<>();

        private final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();

        private void attachCancellation(RunCancellationRegistration registration) {
            Objects.requireNonNull(registration, "registration");
            if (!cancellationRegistration.compareAndSet(null, registration)) {
                registration.close();
                throw new IllegalStateException("Authorization cancellation registration is already attached.");
            }
            if (terminal.get() && cancellationRegistration.compareAndSet(registration, null)) {
                registration.close();
            }
        }

        private void attachTimeout(ScheduledFuture<?> scheduled) {
            Objects.requireNonNull(scheduled, "scheduled");
            if (!timeout.compareAndSet(null, scheduled)) {
                scheduled.cancel(false);
                throw new IllegalStateException("Authorization timeout is already attached.");
            }
            if (terminal.get() && timeout.compareAndSet(scheduled, null)) {
                scheduled.cancel(false);
            }
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private void complete(HostingAuthorizationDecision decision) {
            finish(decision, null);
        }

        private void fail(Throwable failure) {
            finish(null, Objects.requireNonNull(failure, "failure"));
        }

        private void closeDispatcher() {
            fail(new HostingException(
                    HostingErrorCode.CLIENT_CANCELLED, "Hosting dispatcher closed during authorization."));
        }

        private void finish(HostingAuthorizationDecision decision, Throwable failure) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            pendingAuthorizations.remove(this);
            ScheduledFuture<?> scheduled = timeout.getAndSet(null);
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            RunCancellationRegistration registration = cancellationRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            if (failure == null) {
                result.complete(decision);
            } else {
                result.completeExceptionally(failure);
            }
        }
    }

    private record AgentApprovalState(ChatAgent agent, AgentContinuation continuation) {
        private AgentApprovalState {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(continuation, "continuation");
        }
    }

    private record OrchestrationContinuationState(
            HostingRegistry.OrchestrationRegistration<?> registration, OrchestrationContinuation continuation) {
        private OrchestrationContinuationState {
            Objects.requireNonNull(registration, "registration");
            Objects.requireNonNull(continuation, "continuation");
        }
    }

    private record ResumePhase(CompletionStage<HostingOutcome> terminal, RunCancellation cancellation) {
        private ResumePhase {
            Objects.requireNonNull(terminal, "terminal");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }
}
