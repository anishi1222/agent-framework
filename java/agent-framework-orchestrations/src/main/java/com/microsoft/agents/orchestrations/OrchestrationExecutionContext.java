// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.agents.AgentRunResult;
import com.microsoft.agents.agents.AgentSession;
import com.microsoft.agents.agents.ApprovalRequiredException;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.ToolApprovalDecision;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class OrchestrationExecutionContext<O> implements AutoCloseable {
    private final String orchestrationId;

    private final String runId;

    private final OrchestrationRunOptions options;

    private final OrchestrationSessionPolicy sessionPolicy;

    private final RunHandleSource<OrchestrationResult<O>> source;

    private final Consumer<OrchestrationEvent> eventSink;

    private final ArrayList<OrchestrationEvent> events = new ArrayList<>();

    private final AtomicLong invocationSequence = new AtomicLong();

    private final ExecutorService participantExecutor;

    private final boolean ownsParticipantExecutor;

    private final ConcurrentHashMap<String, AgentSession> participantSessions = new ConcurrentHashMap<>();

    private final AgentSession sharedSession;

    private final ConcurrentHashMap<RunCancellation, RunCancellationRegistration> childCancellations =
            new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean();

    private long eventSequence;

    OrchestrationExecutionContext(
            String orchestrationId,
            String runId,
            OrchestrationRunOptions options,
            RunHandleSource<OrchestrationResult<O>> source,
            Consumer<OrchestrationEvent> eventSink) {
        this(orchestrationId, runId, options, source, eventSink, null);
    }

    OrchestrationExecutionContext(
            String orchestrationId,
            String runId,
            OrchestrationRunOptions options,
            RunHandleSource<OrchestrationResult<O>> source,
            Consumer<OrchestrationEvent> eventSink,
            Snapshot snapshot) {
        this.orchestrationId = OrchestrationValidation.requireId(orchestrationId, "orchestrationId");
        this.runId = OrchestrationValidation.requireId(runId, "runId");
        this.options = Objects.requireNonNull(options, "options");
        sessionPolicy = snapshot == null ? options.sessionPolicy() : snapshot.sessionPolicy();
        this.source = Objects.requireNonNull(source, "source");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        ownsParticipantExecutor = options.participantExecutor() == null;
        participantExecutor =
                ownsParticipantExecutor ? Executors.newVirtualThreadPerTaskExecutor() : options.participantExecutor();
        if (snapshot == null) {
            sharedSession = sessionPolicy == OrchestrationSessionPolicy.SHARED
                    ? new AgentSession("orchestration-" + runId + "-shared")
                    : null;
        } else {
            events.addAll(snapshot.events());
            eventSequence = snapshot.nextEventSequence();
            invocationSequence.set(snapshot.nextInvocationSequence());
            participantSessions.putAll(snapshot.participantSessions());
            sharedSession = snapshot.sharedSession();
        }
    }

    String orchestrationId() {
        return orchestrationId;
    }

    String runId() {
        return runId;
    }

    RunCancellation cancellation() {
        return source.cancellation();
    }

    OrchestrationRunOptions options() {
        return options;
    }

    OrchestrationSessionPolicy sessionPolicy() {
        return sessionPolicy;
    }

    synchronized OrchestrationEvent emit(
            OrchestrationEventType type, String participantId, int turn, String correlationId, StateValue data) {
        long sequence = eventSequence++;
        OrchestrationEvent event = new OrchestrationEvent(
                sequence,
                runId + ":event:" + sequence,
                type,
                orchestrationId,
                runId,
                participantId,
                turn,
                correlationId,
                data);
        events.add(event);
        for (OrchestrationEventListener listener : options.eventListeners()) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
                // Optional instrumentation cannot alter orchestration behavior.
            }
        }
        try {
            eventSink.accept(event);
        } catch (RuntimeException overflowOrSinkFailure) {
            source.cancellation().cancel();
            throw overflowOrSinkFailure;
        }
        return event;
    }

    synchronized List<OrchestrationEvent> events() {
        return List.copyOf(events);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                events, eventSequence, invocationSequence.get(), participantSessions, sharedSession, sessionPolicy);
    }

    CompletionStage<ParticipantResult> invoke(OrchestrationParticipant participant, List<Message> messages, int turn) {
        Objects.requireNonNull(participant, "participant");
        List<Message> checkedMessages = OrchestrationValidation.copyMessages(messages);
        CompletableFuture<ParticipantResult> dispatched = new CompletableFuture<>();
        try {
            participantExecutor.execute(() -> beginInvocation(participant, checkedMessages, turn, dispatched));
        } catch (RejectedExecutionException failure) {
            dispatched.completeExceptionally(
                    new OrchestrationExecutionException("Participant executor rejected the invocation.", failure));
        }
        return dispatched;
    }

    void cancelParticipants() {
        childCancellations.keySet().forEach(RunCancellation::cancel);
    }

    CompletionStage<ParticipantResult> resumeApproval(
            OrchestrationParticipant participant,
            AgentContinuation continuation,
            List<ToolApprovalDecision> decisions,
            int turn) {
        requireApprovalResumeSupported(participant, continuation);
        List<ToolApprovalDecision> checkedDecisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (checkedDecisions.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("decisions contains null");
        }
        CompletableFuture<ParticipantResult> dispatched = new CompletableFuture<>();
        try {
            participantExecutor.execute(
                    () -> beginApprovalResume(participant, continuation, checkedDecisions, dispatched));
        } catch (RejectedExecutionException failure) {
            dispatched.completeExceptionally(
                    new OrchestrationExecutionException("Participant executor rejected the resume.", failure));
        }
        return dispatched;
    }

    void requireApprovalResumeSupported(OrchestrationParticipant participant, AgentContinuation continuation) {
        Objects.requireNonNull(participant, "participant");
        AgentContinuation checked = Objects.requireNonNull(continuation, "continuation");
        if (!(participant.agent() instanceof ChatAgent)) {
            throw new OrchestrationContinuationException("Participant '" + participant.id()
                    + "' produced APPROVAL input but its agent does not support orchestration resume.");
        }
        if (sessionPolicy == OrchestrationSessionPolicy.STATELESS) {
            if (checked.sessionId() != null) {
                throw new OrchestrationContinuationException(
                        "A stateless participant produced a session-bound continuation.");
            }
            return;
        }
        AgentSession session = existingSession(participant);
        if (session == null || !session.sessionId().equals(checked.sessionId())) {
            throw new OrchestrationContinuationException(
                    "Approval continuation does not belong to the participant's orchestration session.");
        }
    }

    void abandonApproval(OrchestrationParticipant participant, AgentContinuation continuation) {
        if (participant.agent() instanceof ChatAgent chatAgent && continuation.sessionId() == null) {
            chatAgent.discardContinuation(continuation);
        }
    }

    void emitParticipantResult(ParticipantResult result, int turn, String correlationId) {
        OrchestrationEventType type =
                switch (result.status()) {
                    case COMPLETED -> OrchestrationEventType.PARTICIPANT_COMPLETED;
                    case FAILED -> OrchestrationEventType.PARTICIPANT_FAILED;
                    case INPUT_REQUIRED -> OrchestrationEventType.INPUT_REQUIRED;
                    case SKIPPED -> OrchestrationEventType.PARTICIPANT_SKIPPED;
                };
        LinkedHashMap<String, StateValue> data = new LinkedHashMap<>();
        data.put("participantId", StateValue.string(result.participantId()));
        data.put("status", StateValue.string(result.status().name()));
        result.response().ifPresent(response -> data.put("text", StateValue.string(response.text())));
        result.error().ifPresent(error -> {
            data.put("errorType", StateValue.string(error.errorType()));
            data.put("message", StateValue.string(error.message()));
        });
        emit(type, result.participantId(), turn, correlationId, StateValue.object(data));
    }

    private void beginInvocation(
            OrchestrationParticipant participant,
            List<Message> messages,
            int turn,
            CompletableFuture<ParticipantResult> target) {
        if (source.cancellation().isCancellationRequested()) {
            target.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
            return;
        }
        long invocation = invocationSequence.getAndIncrement();
        String correlationId = runId + ":participant:" + participant.id() + ":" + invocation;
        OrchestrationEvent started = emit(
                OrchestrationEventType.PARTICIPANT_STARTED,
                participant.id(),
                turn,
                correlationId,
                StateValue.object(Map.of("participantId", StateValue.string(participant.id()))));
        DefaultRunCancellation childCancellation = new DefaultRunCancellation();
        RunCancellationRegistration registration =
                RunCancellations.register(source.cancellation(), childCancellation::cancel);
        childCancellations.put(childCancellation, registration);
        RunOptions agentOptions = agentOptions(participant, started.eventId(), correlationId);
        CompletionStage<ParticipantResult> invocationStage;
        try {
            invocationStage = invokeAgent(participant, messages, agentOptions, childCancellation);
        } catch (RuntimeException failure) {
            finishChild(childCancellation);
            target.complete(ParticipantResult.failed(participant.id(), failure));
            return;
        }
        if (invocationStage == null) {
            finishChild(childCancellation);
            target.complete(ParticipantResult.failed(
                    participant.id(),
                    new OrchestrationExecutionException("Participant invocation returned a null stage.")));
            return;
        }
        invocationStage.whenComplete((result, failure) -> {
            finishChild(childCancellation);
            if (failure == null) {
                target.complete(result);
                return;
            }
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof ApprovalRequiredException approval) {
                target.complete(ParticipantResult.inputRequired(participant.id(), approval.continuation()));
            } else if (source.cancellation().isCancellationRequested()) {
                target.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
            } else {
                target.complete(ParticipantResult.failed(participant.id(), cause));
            }
        });
    }

    private void beginApprovalResume(
            OrchestrationParticipant participant,
            AgentContinuation continuation,
            List<ToolApprovalDecision> decisions,
            CompletableFuture<ParticipantResult> target) {
        if (source.cancellation().isCancellationRequested()) {
            target.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
            return;
        }
        ChatAgent chatAgent = (ChatAgent) participant.agent();
        DefaultRunCancellation childCancellation = new DefaultRunCancellation();
        RunCancellationRegistration registration =
                RunCancellations.register(source.cancellation(), childCancellation::cancel);
        childCancellations.put(childCancellation, registration);
        CompletionStage<AgentRunResult<Void>> resumeStage;
        try {
            resumeStage = sessionPolicy == OrchestrationSessionPolicy.STATELESS
                    ? chatAgent.resumeAsync(continuation, decisions, childCancellation)
                    : chatAgent.resumeAsync(
                            Objects.requireNonNull(existingSession(participant), "participant session"),
                            continuation,
                            decisions,
                            childCancellation);
        } catch (RuntimeException failure) {
            finishChild(childCancellation);
            target.completeExceptionally(failure);
            return;
        }
        if (resumeStage == null) {
            finishChild(childCancellation);
            target.completeExceptionally(
                    new OrchestrationExecutionException("Participant resume returned a null stage."));
            return;
        }
        resumeStage.whenComplete((result, failure) -> {
            finishChild(childCancellation);
            if (failure == null) {
                target.complete(mapSessionResult(participant.id(), result));
            } else if (source.cancellation().isCancellationRequested()) {
                target.completeExceptionally(new com.microsoft.agents.core.RunCancelledException());
            } else {
                target.complete(ParticipantResult.failed(participant.id(), RunHandles.unwrap(failure)));
            }
        });
    }

    private CompletionStage<ParticipantResult> invokeAgent(
            OrchestrationParticipant participant,
            List<Message> messages,
            RunOptions agentOptions,
            RunCancellation childCancellation) {
        if (participant.agent() instanceof ChatAgent chatAgent
                && sessionPolicy != OrchestrationSessionPolicy.STATELESS) {
            AgentSession session = sessionFor(participant);
            List<Message> incremental = OrchestrationMessages.incrementalMessages(session.messages(), messages);
            return chatAgent
                    .runAsync(session, incremental, agentOptions, childCancellation)
                    .thenApply(result -> mapSessionResult(participant.id(), result));
        }
        return invokeGeneric(participant.agent(), messages, agentOptions, childCancellation)
                .thenApply(response -> ParticipantResult.completed(participant.id(), response));
    }

    private AgentSession sessionFor(OrchestrationParticipant participant) {
        if (sharedSession != null) {
            return sharedSession;
        }
        return participantSessions.computeIfAbsent(
                participant.id(), ignored -> new AgentSession("orchestration-" + runId + "-" + participant.id()));
    }

    private AgentSession existingSession(OrchestrationParticipant participant) {
        return sharedSession == null ? participantSessions.get(participant.id()) : sharedSession;
    }

    private RunOptions agentOptions(OrchestrationParticipant participant, String eventId, String correlationId) {
        RunOptions configured = options.agentRunOptions();
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(configured.metadata());
        metadata.putAll(options.metadata());
        metadata.put("orchestration.id", StateValue.string(orchestrationId));
        metadata.put("orchestration.run.id", StateValue.string(runId));
        metadata.put("orchestration.event.id", StateValue.string(eventId));
        metadata.put("orchestration.correlation.id", StateValue.string(correlationId));
        metadata.put("orchestration.participant.id", StateValue.string(participant.id()));
        return new RunOptions(configured.maxIterations(), configured.maxFunctionCalls(), metadata);
    }

    private static ParticipantResult mapSessionResult(String participantId, AgentRunResult<Void> result) {
        if (result.response().isPresent()) {
            return ParticipantResult.completed(participantId, result.response().orElseThrow());
        }
        return ParticipantResult.inputRequired(
                participantId, result.continuation().orElseThrow());
    }

    private static CompletionStage<AgentResponse<?>> invokeGeneric(
            Agent<?> agent, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return invokeCaptured(agent, messages, options, cancellation);
    }

    private static <T> CompletionStage<AgentResponse<?>> invokeCaptured(
            Agent<T> agent, List<Message> messages, RunOptions options, RunCancellation cancellation) {
        RunHandle<AgentResponse<T>> handle = agent.startRun(messages, options, cancellation);
        return handle.resultAsync().thenApply(response -> response);
    }

    private void finishChild(RunCancellation cancellation) {
        RunCancellationRegistration registration = childCancellations.remove(cancellation);
        if (registration != null) {
            registration.close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        childCancellations.forEach((cancellation, registration) -> {
            cancellation.cancel();
            registration.close();
        });
        childCancellations.clear();
        if (ownsParticipantExecutor) {
            participantExecutor.close();
        }
    }

    static String newRunId(OrchestrationRunOptions options) {
        return options.runId() == null ? UUID.randomUUID().toString() : options.runId();
    }

    static CompletionException propagate(Throwable failure) {
        return new CompletionException(RunHandles.unwrap(failure));
    }

    record Snapshot(
            List<OrchestrationEvent> events,
            long nextEventSequence,
            long nextInvocationSequence,
            Map<String, AgentSession> participantSessions,
            AgentSession sharedSession,
            OrchestrationSessionPolicy sessionPolicy) {
        Snapshot {
            events = List.copyOf(events);
            participantSessions = Map.copyOf(participantSessions);
            Objects.requireNonNull(sessionPolicy, "sessionPolicy");
            if (nextEventSequence < 0 || nextInvocationSequence < 0) {
                throw new IllegalArgumentException("Snapshot sequences must not be negative.");
            }
        }
    }
}
