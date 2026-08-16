// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.Experimental;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Exposes steps, human input, state, metadata, and custom events to a functional workflow body.
 */
@Experimental("FUNCTIONAL_WORKFLOWS")
public final class FunctionalRunContext {
    private final SharedState shared;

    private final ActiveStep activeStep;

    FunctionalRunContext(SharedState shared, ActiveStep activeStep) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.activeStep = activeStep;
    }

    /**
     * Executes or replays one typed functional step.
     *
     * @param step functional step
     * @param input step input
     * @param <I> step input type
     * @param <O> step output type
     * @return asynchronous step output
     */
    public <I, O> CompletionStage<O> runStepAsync(FunctionalStep<I, O> step, I input) {
        return shared.runStep(step, input);
    }

    /**
     * Requests typed external information using a deterministic generated identifier.
     *
     * <p>When no response is available, the current invocation pauses and returns an
     * input-required result to the caller. The generated identifier has the form
     * {@code auto::<index>} and remains stable when completed steps are replayed.
     * Workflow and step bodies must not intercept {@link Error}; the runtime uses a private
     * non-stack-traced error as its interruption signal so ordinary {@code catch (Exception)}
     * blocks cannot accidentally consume it.
     *
     * @param requestData immutable request payload
     * @param responseType expected response type
     * @param responseCodec response codec
     * @param <T> response type
     * @return supplied response during replay
     */
    public <T> T requestInfo(StateValue requestData, Class<T> responseType, StateCodec<T> responseCodec) {
        return requestInfo(null, requestData, responseType, responseCodec);
    }

    /**
     * Requests typed external information using an explicit stable identifier.
     *
     * <p>Workflow and step bodies must not intercept {@link Error}; see the generated-identifier
     * overload for the interruption rationale.
     *
     * @param requestId stable request identifier, or {@code null} to generate one
     * @param requestData immutable request payload
     * @param responseType expected response type
     * @param responseCodec response codec
     * @param <T> response type
     * @return supplied response during replay
     */
    public <T> T requestInfo(
            String requestId, StateValue requestData, Class<T> responseType, StateCodec<T> responseCodec) {
        Objects.requireNonNull(requestData, "requestData");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(responseCodec, "responseCodec");
        WorkflowValidation.requireCodec(responseCodec);
        shared.throwIfCancelled();

        String resolvedId;
        synchronized (shared) {
            resolvedId = requestId == null ? shared.nextAutoRequestId() : requireRequestId(requestId);
            if (activeStep != null && requestId == null) {
                activeStep.autoRequestCount++;
            }
            EncodedState response = shared.responses.remove(resolvedId);
            if (response != null) {
                shared.resolvedResponses.put(resolvedId, response);
            } else {
                response = shared.resolvedResponses.get(resolvedId);
            }
            if (response != null) {
                shared.pendingRequests.remove(resolvedId);
                if (activeStep != null) {
                    activeStep.consumedResponseIds.add(resolvedId);
                }
                return FunctionalStateCodecSupport.decode(
                        "Functional workflow response '" + resolvedId + "'", responseType, responseCodec, response);
            }
        }

        FunctionalInputRequest request = new FunctionalInputRequest(
                resolvedId,
                activeStep == null ? shared.workflowId : activeStep.invocation.stepName(),
                requestData,
                responseCodec.typeId(),
                responseCodec.currentVersion());
        synchronized (shared) {
            FunctionalInputRequest existing = shared.pendingRequests.get(resolvedId);
            if (existing != null && !existing.equals(request)) {
                throw new WorkflowException(
                        "Pending request '" + resolvedId + "' changed while replaying the functional workflow.");
            }
            shared.pendingRequests.put(resolvedId, request);
        }
        shared.emit(
                WorkflowEventType.INPUT_REQUESTED,
                activeStep == null ? null : new NodeId(activeStep.invocation.stepName()),
                activeStep == null ? null : activeStep.invocation.correlationId(),
                StateValue.object(Map.of(
                        "requestId",
                        StateValue.string(request.requestId()),
                        "sourceId",
                        StateValue.string(request.sourceId()),
                        "requestData",
                        request.data(),
                        "responseTypeId",
                        StateValue.string(request.responseTypeId()),
                        "responseVersion",
                        StateValue.integer(request.responseVersion()))));
        throw new FunctionalWorkflowInterrupted(request);
    }

    /**
     * Reads one typed workflow-scoped state value.
     *
     * @param key state key
     * @param <T> state value type
     * @return decoded value when present
     */
    public <T> java.util.Optional<T> getState(StateKey<T> key) {
        Objects.requireNonNull(key, "key");
        requireUserStateKey(key.name());
        synchronized (shared) {
            EncodedState encoded = shared.userState.get(key.name());
            return encoded == null ? java.util.Optional.empty() : java.util.Optional.of(key.decode(encoded));
        }
    }

    /**
     * Stores one typed workflow-scoped state value.
     *
     * @param key state key
     * @param value state value
     * @param <T> state value type
     */
    public <T> void setState(StateKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        requireUserStateKey(key.name());
        shared.throwIfCancelled();
        synchronized (shared) {
            shared.userState.put(key.name(), key.encode(value));
        }
    }

    /**
     * Emits an application-defined event.
     *
     * @param name stable event name
     * @param data immutable JSON-shaped event data
     */
    public void addEvent(String name, StateValue data) {
        String checkedName = WorkflowValidation.requireNonBlank(name, "event name");
        shared.emit(
                WorkflowEventType.CUSTOM,
                activeStep == null ? null : new NodeId(activeStep.invocation.stepName()),
                activeStep == null ? null : activeStep.invocation.correlationId(),
                StateValue.object(Map.of(
                        "name", StateValue.string(checkedName),
                        "data", Objects.requireNonNull(data, "data"))));
    }

    /**
     * Returns immutable run metadata.
     *
     * @return run metadata
     */
    public Map<String, StateValue> metadata() {
        return shared.metadata;
    }

    /**
     * Reports whether this invocation is delivering a streaming event publisher.
     *
     * @return {@code true} for streaming execution
     */
    public boolean isStreaming() {
        return shared.streaming;
    }

    /**
     * Returns the run cancellation signal.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return shared.cancellation;
    }

    SharedState shared() {
        return shared;
    }

    private static String requireRequestId(String requestId) {
        return WorkflowValidation.requireNonBlank(requestId, "requestId");
    }

    private static void requireUserStateKey(String key) {
        if (key.startsWith("_")) {
            throw new WorkflowValidationException(
                    "Functional workflow user state keys must not start with reserved prefix '_'.");
        }
    }

    static final class SharedState {
        private final String workflowId;

        private final String runId;

        private final EncodedState originalInput;

        private final RunCancellation cancellation;

        private final Consumer<WorkflowEvent> eventConsumer;

        private final Executor stepExecutor;

        private final Map<String, StateValue> metadata;

        private final boolean streaming;

        private final LinkedHashMap<FunctionalStepInvocation, FunctionalCachedStep> stepCache;

        private final LinkedHashMap<String, EncodedState> userState;

        private final LinkedHashMap<String, FunctionalInputRequest> pendingRequests;

        private final LinkedHashMap<String, EncodedState> responses;

        private final LinkedHashMap<String, EncodedState> resolvedResponses;

        private final LinkedHashMap<String, Integer> stepCallCounters = new LinkedHashMap<>();

        private final ArrayList<WorkflowEvent> events = new ArrayList<>();

        private long eventSequence;

        private int autoRequestIndex;

        private int completedLiveSteps;

        private int checkpointOrdinal;

        private boolean acceptingEvents = true;

        private StepCheckpointSaver stepCheckpointSaver = ignored -> CompletableFuture.completedFuture(null);

        SharedState(
                String workflowId,
                String runId,
                EncodedState originalInput,
                FunctionalWorkflowSnapshot snapshot,
                FunctionalWorkflowResponses responses,
                RunCancellation cancellation,
                Consumer<WorkflowEvent> eventConsumer,
                Executor stepExecutor,
                Map<String, StateValue> metadata,
                boolean streaming) {
            this.workflowId = WorkflowValidation.requireNonBlank(workflowId, "workflowId");
            this.runId = WorkflowValidation.requireNonBlank(runId, "runId");
            this.originalInput = Objects.requireNonNull(originalInput, "originalInput");
            this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
            this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor");
            this.metadata = Map.copyOf(metadata);
            this.streaming = streaming;
            this.stepCache = new LinkedHashMap<>(snapshot == null ? Map.of() : snapshot.stepCache());
            this.userState = new LinkedHashMap<>(snapshot == null ? Map.of() : snapshot.userState());
            this.pendingRequests = new LinkedHashMap<>(snapshot == null ? Map.of() : snapshot.pendingRequests());
            this.responses = new LinkedHashMap<>(responses.values());
            this.resolvedResponses = new LinkedHashMap<>(snapshot == null ? Map.of() : snapshot.resolvedResponses());
            this.completedLiveSteps = snapshot == null ? 0 : snapshot.completedLiveSteps();
            this.checkpointOrdinal = snapshot == null ? 0 : snapshot.checkpointOrdinal();
            validateResponses();
        }

        synchronized <I, O> CompletionStage<O> runStep(FunctionalStep<I, O> step, I input) {
            Objects.requireNonNull(step, "step");
            throwIfCancelled();
            I checkedInput = step.inputType().cast(Objects.requireNonNull(input, "input"));
            int callIndex = stepCallCounters.getOrDefault(step.name(), 0);
            stepCallCounters.put(step.name(), callIndex + 1);
            FunctionalStepInvocation invocation = new FunctionalStepInvocation(step.name(), callIndex);
            FunctionalCachedStep cached = stepCache.get(invocation);
            if (cached != null) {
                autoRequestIndex += cached.autoRequestCount();
                O output = FunctionalStateCodecSupport.decode(
                        "Functional step '" + invocation.correlationId() + "'",
                        step.outputType(),
                        step.outputCodec(),
                        cached.output());
                emit(
                        WorkflowEventType.NODE_BYPASSED,
                        new NodeId(step.name()),
                        invocation.correlationId(),
                        StateValue.object(Map.of(
                                "callIndex",
                                StateValue.integer(callIndex),
                                "output",
                                cached.output().value())));
                return CompletableFuture.completedFuture(output);
            }

            EncodedState encodedInput = FunctionalStateCodecSupport.encode(step.inputCodec(), checkedInput);
            emit(
                    WorkflowEventType.NODE_STARTED,
                    new NodeId(step.name()),
                    invocation.correlationId(),
                    StateValue.object(
                            Map.of("callIndex", StateValue.integer(callIndex), "input", encodedInput.value())));
            ActiveStep active = new ActiveStep(invocation);
            FunctionalRunContext child = new FunctionalRunContext(this, active);
            try {
                CompletableFuture<O> result = new CompletableFuture<>();
                CompletableFuture.runAsync(
                        () -> invokeStep(step, checkedInput, invocation, child, active, result), stepExecutor);
                return result.minimalCompletionStage();
            } catch (Throwable failure) {
                return failedStep(step, invocation, failure);
            }
        }

        private <I, O> void invokeStep(
                FunctionalStep<I, O> step,
                I input,
                FunctionalStepInvocation invocation,
                FunctionalRunContext child,
                ActiveStep active,
                CompletableFuture<O> result) {
            CompletionStage<O> stage;
            try {
                throwIfCancelled();
                stage = Objects.requireNonNull(step.invoke(input, child), "step stage");
            } catch (Throwable failure) {
                completeStepFailure(step, invocation, failure, result);
                return;
            }
            stage.whenCompleteAsync(
                    (output, failure) -> completeStep(step, invocation, active, output, failure, result), stepExecutor);
        }

        private <O> void completeStep(
                FunctionalStep<?, O> step,
                FunctionalStepInvocation invocation,
                ActiveStep active,
                O output,
                Throwable failure,
                CompletableFuture<O> result) {
            Throwable cause = failure == null ? null : RunHandles.unwrap(failure);
            if (cause != null) {
                completeStepFailure(step, invocation, cause, result);
                return;
            }
            try {
                throwIfCancelled();
                O checkedOutput = step.outputType().cast(output);
                EncodedState encodedOutput = FunctionalStateCodecSupport.encode(step.outputCodec(), checkedOutput);
                synchronized (this) {
                    active.consumedResponseIds.forEach(resolvedResponses::remove);
                    stepCache.put(invocation, new FunctionalCachedStep(encodedOutput, active.autoRequestCount));
                    completedLiveSteps++;
                }
                emit(
                        WorkflowEventType.NODE_COMPLETED,
                        new NodeId(step.name()),
                        invocation.correlationId(),
                        StateValue.object(Map.of(
                                "callIndex",
                                StateValue.integer(invocation.callIndex()),
                                "output",
                                encodedOutput.value())));
                throwIfCancelled();
                stepCheckpointSaver.save(this).whenComplete((ignored, saveFailure) -> {
                    if (saveFailure == null) {
                        try {
                            throwIfCancelled();
                            result.complete(checkedOutput);
                        } catch (Throwable cancellationFailure) {
                            result.completeExceptionally(cancellationFailure);
                        }
                    } else {
                        result.completeExceptionally(RunHandles.unwrap(saveFailure));
                    }
                });
            } catch (Throwable completionFailure) {
                result.completeExceptionally(completionFailure);
            }
        }

        synchronized String nextAutoRequestId() {
            return "auto::" + autoRequestIndex++;
        }

        synchronized void emit(WorkflowEventType type, NodeId nodeId, String correlationId, StateValue data) {
            if (!acceptingEvents && type != WorkflowEventType.RUN_CANCELLED) {
                return;
            }
            WorkflowEvent event = new WorkflowEvent(eventSequence++, type, runId, nodeId, -1, correlationId, data);
            if (!streaming) {
                events.add(event);
            }
            eventConsumer.accept(event);
        }

        synchronized List<WorkflowEvent> events() {
            return List.copyOf(events);
        }

        String runId() {
            return runId;
        }

        synchronized List<FunctionalInputRequest> pendingRequests() {
            return pendingRequests.values().stream()
                    .sorted(java.util.Comparator.comparing(FunctionalInputRequest::requestId))
                    .toList();
        }

        synchronized FunctionalWorkflowSnapshot snapshot() {
            return new FunctionalWorkflowSnapshot(
                    originalInput,
                    stepCache,
                    userState,
                    pendingRequests,
                    resolvedResponses,
                    completedLiveSteps,
                    checkpointOrdinal);
        }

        synchronized int incrementCheckpointOrdinal() {
            return ++checkpointOrdinal;
        }

        synchronized int completedLiveSteps() {
            return completedLiveSteps;
        }

        synchronized void stopAcceptingEvents() {
            acceptingEvents = false;
        }

        synchronized void stepCheckpointSaver(StepCheckpointSaver saver) {
            stepCheckpointSaver = Objects.requireNonNull(saver, "saver");
        }

        void throwIfCancelled() {
            if (cancellation.isCancellationRequested()) {
                throw new RunCancelledException();
            }
        }

        private void validateResponses() {
            if (responses.isEmpty()) {
                return;
            }
            if (pendingRequests.isEmpty() && resolvedResponses.isEmpty()) {
                throw new WorkflowValidationException(
                        "Functional workflow responses were supplied but no input requests are pending.");
            }
            TreeMap<String, EncodedState> unexpected = new TreeMap<>(responses);
            unexpected.keySet().removeAll(pendingRequests.keySet());
            for (Map.Entry<String, EncodedState> resolved : resolvedResponses.entrySet()) {
                EncodedState repeated = unexpected.get(resolved.getKey());
                if (repeated != null && repeated.equals(resolved.getValue())) {
                    unexpected.remove(resolved.getKey());
                } else if (repeated != null) {
                    throw new WorkflowValidationException(
                            "Response for already resolved request '" + resolved.getKey() + "' changed during replay.");
                }
            }
            if (!unexpected.isEmpty()) {
                throw new WorkflowValidationException(
                        "Responses do not match pending request identifiers: " + unexpected.keySet() + ".");
            }
        }

        private <I, O> CompletionStage<O> failedStep(
                FunctionalStep<I, O> step, FunctionalStepInvocation invocation, Throwable failure) {
            CompletableFuture<O> result = new CompletableFuture<>();
            completeStepFailure(step, invocation, failure, result);
            return result.minimalCompletionStage();
        }

        private <O> void completeStepFailure(
                FunctionalStep<?, O> step,
                FunctionalStepInvocation invocation,
                Throwable failure,
                CompletableFuture<O> result) {
            Throwable cause = RunHandles.unwrap(failure);
            if (!(cause instanceof FunctionalWorkflowInterrupted)) {
                emit(
                        WorkflowEventType.NODE_FAILED,
                        new NodeId(step.name()),
                        invocation.correlationId(),
                        errorData(cause));
            }
            result.completeExceptionally(cause);
        }

        private static StateValue errorData(Throwable failure) {
            return StateValue.object(Map.of(
                    "errorType",
                    StateValue.string(failure.getClass().getSimpleName()),
                    "message",
                    StateValue.string(failure.getMessage() == null ? "" : failure.getMessage())));
        }
    }

    private static final class ActiveStep {
        private final FunctionalStepInvocation invocation;

        private int autoRequestCount;

        private final LinkedHashSet<String> consumedResponseIds = new LinkedHashSet<>();

        private ActiveStep(FunctionalStepInvocation invocation) {
            this.invocation = invocation;
        }
    }

    @FunctionalInterface
    interface StepCheckpointSaver {
        CompletionStage<Void> save(SharedState state);
    }
}
