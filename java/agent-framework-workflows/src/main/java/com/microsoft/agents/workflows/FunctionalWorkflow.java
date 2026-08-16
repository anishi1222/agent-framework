// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.Experimental;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.VersionedSnapshot;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Executes a user-defined asynchronous function with deterministic step replay and checkpointing.
 *
 * <p>This runtime is intentionally separate from the graph/superstep workflow engine. Java control
 * flow remains inside the supplied function, while tracked {@link FunctionalStep} calls provide
 * cache replay, lifecycle events, and per-step checkpoint boundaries.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
@Experimental("FUNCTIONAL_WORKFLOWS")
public final class FunctionalWorkflow<I, O> implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;

    private final String id;

    private final String description;

    private final Class<I> inputType;

    private final Class<O> outputType;

    private final StateCodec<I> inputCodec;

    private final StateCodec<O> outputCodec;

    private final FunctionalWorkflowFunction<I, O> function;

    private final String fingerprint;

    private final CheckpointStorage defaultCheckpointStorage;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicBoolean active = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicReference<RunHandle<FunctionalWorkflowRunResult<O>>> activeHandle = new AtomicReference<>();

    private final AtomicReference<Continuation> continuation = new AtomicReference<>();

    private FunctionalWorkflow(Builder<I, O> builder) {
        id = builder.id;
        description = builder.description;
        inputType = builder.inputType;
        outputType = builder.outputType;
        inputCodec = builder.inputCodec;
        outputCodec = builder.outputCodec;
        function = Objects.requireNonNull(builder.function, "function");
        defaultCheckpointStorage = builder.defaultCheckpointStorage;
        fingerprint = fingerprint(id, inputType, outputType, inputCodec, outputCodec, builder.signatureVersion);
    }

    /**
     * Creates a functional workflow builder.
     *
     * @param id stable workflow identifier
     * @param inputType workflow input type
     * @param outputType workflow output type
     * @param inputCodec workflow input codec
     * @param outputCodec workflow output codec
     * @param <I> input type
     * @param <O> output type
     * @return workflow builder
     */
    public static <I, O> Builder<I, O> builder(
            String id, Class<I> inputType, Class<O> outputType, StateCodec<I> inputCodec, StateCodec<O> outputCodec) {
        return new Builder<>(id, inputType, outputType, inputCodec, outputCodec);
    }

    /**
     * Returns the stable workflow identifier.
     *
     * @return workflow identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the optional workflow description.
     *
     * @return description, or {@code null}
     */
    public String description() {
        return description;
    }

    /**
     * Adapts this functional workflow to the provider-neutral agent contract.
     *
     * <p>The returned agent is non-owning: closing it does not close this workflow. Pending
     * {@link FunctionalRunContext#requestInfo} calls are exposed as informational
     * {@link com.microsoft.agents.core.FunctionCallContent} values, and matching
     * {@link com.microsoft.agents.core.FunctionResultContent} values resume the invocation.
     *
     * @param metadata agent identity and display metadata
     * @param inputMapper converts ordered agent messages to the workflow input
     * @param outputMapper converts a workflow output to ordered agent messages
     * @return functional workflow agent
     */
    public FunctionalWorkflowAgent<I, O> asAgent(
            AgentMetadata metadata,
            Function<List<Message>, ? extends I> inputMapper,
            Function<? super O, ? extends List<Message>> outputMapper) {
        return new FunctionalWorkflowAgent<>(this, metadata, inputMapper, outputMapper);
    }

    /**
     * Returns the deterministic signature fingerprint used for checkpoint validation.
     *
     * @return SHA-256 fingerprint
     */
    public String fingerprint() {
        return fingerprint;
    }

    O decodeOutput(StateValue value) {
        return FunctionalStateCodecSupport.decode(
                "Functional workflow output",
                outputType,
                outputCodec,
                new EncodedState(outputCodec.typeId(), outputCodec.currentVersion(), value));
    }

    /**
     * Runs a fresh workflow asynchronously with default options.
     *
     * @param input workflow input
     * @return terminal result stage
     */
    public CompletionStage<FunctionalWorkflowRunResult<O>> runAsync(I input) {
        return runAsync(input, FunctionalWorkflowRunOptions.defaults());
    }

    /**
     * Runs a fresh workflow asynchronously.
     *
     * @param input workflow input
     * @param options run options
     * @return terminal result stage
     */
    public CompletionStage<FunctionalWorkflowRunResult<O>> runAsync(I input, FunctionalWorkflowRunOptions options) {
        return startRun(input, options, new DefaultRunCancellation()).resultAsync();
    }

    /**
     * Runs a fresh workflow synchronously with default options.
     *
     * @param input workflow input
     * @return terminal result
     */
    public FunctionalWorkflowRunResult<O> run(I input) {
        return run(input, FunctionalWorkflowRunOptions.defaults());
    }

    /**
     * Runs a fresh workflow synchronously.
     *
     * @param input workflow input
     * @param options run options
     * @return terminal result
     */
    public FunctionalWorkflowRunResult<O> run(I input, FunctionalWorkflowRunOptions options) {
        return RunHandles.await(startRun(input, options, new DefaultRunCancellation()), "Functional workflow run");
    }

    /**
     * Starts a cancellable fresh workflow invocation.
     *
     * @param input workflow input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    public RunHandle<FunctionalWorkflowRunResult<O>> startRun(
            I input, FunctionalWorkflowRunOptions options, RunCancellation cancellation) {
        I checkedInput = inputType.cast(Objects.requireNonNull(input, "input"));
        return startExecution(
                ExecutionRequest.fresh(checkedInput),
                Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(cancellation, "cancellation"),
                event -> {},
                false);
    }

    /**
     * Returns a cold, bounded, single-subscriber publisher for a fresh invocation.
     *
     * @param input workflow input
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> runStreaming(I input) {
        return runStreaming(input, FunctionalWorkflowRunOptions.defaults(), new DefaultRunCancellation());
    }

    /**
     * Returns a cold event publisher for a fresh invocation.
     *
     * @param input workflow input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> runStreaming(
            I input, FunctionalWorkflowRunOptions options, RunCancellation cancellation) {
        I checkedInput = inputType.cast(Objects.requireNonNull(input, "input"));
        return streamingPublisher(
                ExecutionRequest.fresh(checkedInput),
                Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Resumes the latest input-required invocation asynchronously.
     *
     * @param responses typed responses for pending requests
     * @return terminal result stage
     */
    public CompletionStage<FunctionalWorkflowRunResult<O>> resumeAsync(FunctionalWorkflowResponses responses) {
        return resumeAsync(responses, FunctionalWorkflowRunOptions.defaults());
    }

    /**
     * Resumes the latest input-required invocation asynchronously.
     *
     * @param responses typed responses for pending requests
     * @param options event and metadata options
     * @return terminal result stage
     */
    public CompletionStage<FunctionalWorkflowRunResult<O>> resumeAsync(
            FunctionalWorkflowResponses responses, FunctionalWorkflowRunOptions options) {
        return startResume(responses, options, new DefaultRunCancellation()).resultAsync();
    }

    /**
     * Resumes the latest input-required invocation synchronously.
     *
     * @param responses typed responses for pending requests
     * @return terminal result
     */
    public FunctionalWorkflowRunResult<O> resume(FunctionalWorkflowResponses responses) {
        return RunHandles.await(
                startResume(responses, FunctionalWorkflowRunOptions.defaults(), new DefaultRunCancellation()),
                "Functional workflow resume");
    }

    /**
     * Starts a cancellable response-only replay of the latest input-required invocation.
     *
     * @param responses typed responses for pending requests
     * @param options event and metadata options
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    public RunHandle<FunctionalWorkflowRunResult<O>> startResume(
            FunctionalWorkflowResponses responses, FunctionalWorkflowRunOptions options, RunCancellation cancellation) {
        Continuation current = continuation.get();
        if (current == null) {
            throw new WorkflowException("Functional workflow '" + id + "' has no pending invocation to resume.");
        }
        requireNoCheckpointOverride(options);
        return startExecution(
                ExecutionRequest.continuation(current, Objects.requireNonNull(responses, "responses")),
                options,
                Objects.requireNonNull(cancellation, "cancellation"),
                event -> {},
                false);
    }

    /**
     * Returns a cold event publisher for response-only replay.
     *
     * @param responses typed responses for pending requests
     * @param options event and metadata options
     * @param cancellation caller-owned cancellation
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> resumeStreaming(
            FunctionalWorkflowResponses responses, FunctionalWorkflowRunOptions options, RunCancellation cancellation) {
        Continuation current = continuation.get();
        if (current == null) {
            throw new WorkflowException("Functional workflow '" + id + "' has no pending invocation to resume.");
        }
        requireNoCheckpointOverride(options);
        return streamingPublisher(
                ExecutionRequest.continuation(current, Objects.requireNonNull(responses, "responses")),
                options,
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    /**
     * Loads and resumes a persisted functional workflow checkpoint asynchronously.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param responses optional typed responses for pending requests
     * @param options event and metadata options
     * @return terminal result stage
     */
    public CompletionStage<FunctionalWorkflowRunResult<O>> resumeAsync(
            CheckpointStorage storage,
            CheckpointKey key,
            FunctionalWorkflowResponses responses,
            FunctionalWorkflowRunOptions options) {
        return startCheckpointResume(storage, key, responses, options, new DefaultRunCancellation())
                .resultAsync();
    }

    /**
     * Loads and resumes a persisted functional workflow checkpoint synchronously.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param responses optional typed responses for pending requests
     * @param options event and metadata options
     * @return terminal result
     */
    public FunctionalWorkflowRunResult<O> resume(
            CheckpointStorage storage,
            CheckpointKey key,
            FunctionalWorkflowResponses responses,
            FunctionalWorkflowRunOptions options) {
        return RunHandles.await(
                startCheckpointResume(storage, key, responses, options, new DefaultRunCancellation()),
                "Functional workflow checkpoint resume");
    }

    /**
     * Starts a cancellable persisted-checkpoint resume.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param responses optional typed responses for pending requests
     * @param options event and metadata options
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    public RunHandle<FunctionalWorkflowRunResult<O>> startCheckpointResume(
            CheckpointStorage storage,
            CheckpointKey key,
            FunctionalWorkflowResponses responses,
            FunctionalWorkflowRunOptions options,
            RunCancellation cancellation) {
        requireNoCheckpointOverride(options);
        return startExecution(
                ExecutionRequest.checkpoint(
                        Objects.requireNonNull(storage, "storage"),
                        Objects.requireNonNull(key, "key"),
                        Objects.requireNonNull(responses, "responses")),
                options,
                Objects.requireNonNull(cancellation, "cancellation"),
                event -> {},
                false);
    }

    /**
     * Returns a cold event publisher for a persisted-checkpoint resume.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param responses optional typed responses for pending requests
     * @param options event and metadata options
     * @param cancellation caller-owned cancellation
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> resumeStreaming(
            CheckpointStorage storage,
            CheckpointKey key,
            FunctionalWorkflowResponses responses,
            FunctionalWorkflowRunOptions options,
            RunCancellation cancellation) {
        requireNoCheckpointOverride(options);
        return streamingPublisher(
                ExecutionRequest.checkpoint(
                        Objects.requireNonNull(storage, "storage"),
                        Objects.requireNonNull(key, "key"),
                        Objects.requireNonNull(responses, "responses")),
                options,
                Objects.requireNonNull(cancellation, "cancellation"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RunHandle<FunctionalWorkflowRunResult<O>> current = activeHandle.get();
        if (current != null) {
            current.cancel();
        }
        executor.close();
    }

    private RunHandle<FunctionalWorkflowRunResult<O>> startExecution(
            ExecutionRequest<I> request,
            FunctionalWorkflowRunOptions options,
            RunCancellation cancellation,
            Consumer<WorkflowEvent> events,
            boolean streaming) {
        requireOpen();
        if (!active.compareAndSet(false, true)) {
            throw new WorkflowException(
                    "Functional workflow '" + id + "' is already running; concurrent executions are not allowed.");
        }
        RunHandleSource<FunctionalWorkflowRunResult<O>> source = new RunHandleSource<>(cancellation);
        activeHandle.set(source.handle());
        CompletableFuture<FunctionalWorkflowRunResult<O>> control = CompletableFuture.supplyAsync(
                () -> execute(request, options, source.cancellation(), events, streaming), executor);
        control.whenComplete((result, failure) -> {
            activeHandle.compareAndSet(source.handle(), null);
            active.set(false);
            if (failure == null) {
                source.tryComplete(result);
                return;
            }
            Throwable cause = source.cancellation().isCancellationRequested()
                    ? new RunCancelledException()
                    : RunHandles.unwrap(failure);
            source.tryFail(cause);
        });
        return source.handle();
    }

    private Flow.Publisher<WorkflowEvent> streamingPublisher(
            ExecutionRequest<I> request, FunctionalWorkflowRunOptions options, RunCancellation cancellation) {
        AtomicReference<RunHandle<FunctionalWorkflowRunResult<O>>> handle = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<WorkflowEvent>> publisherReference = new AtomicReference<>();
        SingleSubscriberPublisher<WorkflowEvent> publisher = new SingleSubscriberPublisher<>(
                () -> {
                    SingleSubscriberPublisher<WorkflowEvent> current = publisherReference.get();
                    RunHandle<FunctionalWorkflowRunResult<O>> run =
                            startExecution(request, options, cancellation, current::emit, true);
                    handle.set(run);
                    run.resultAsync().whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            current.complete();
                        } else {
                            current.fail(RunHandles.unwrap(failure));
                        }
                    });
                },
                () -> {
                    RunHandle<FunctionalWorkflowRunResult<O>> current = handle.get();
                    if (current != null) {
                        current.cancel();
                    } else {
                        cancellation.cancel();
                    }
                },
                options.maxBufferedEvents(),
                WorkflowStreamingBufferOverflowException::new);
        publisherReference.set(publisher);
        return publisher;
    }

    private FunctionalWorkflowRunResult<O> execute(
            ExecutionRequest<I> request,
            FunctionalWorkflowRunOptions options,
            RunCancellation cancellation,
            Consumer<WorkflowEvent> eventConsumer,
            boolean streaming) {
        cancellationCheck(cancellation);
        ResolvedExecution<I> resolved = resolve(request, options);
        FunctionalRunContext.SharedState shared = new FunctionalRunContext.SharedState(
                id,
                resolved.runId(),
                resolved.snapshot().originalInput(),
                resolved.snapshot(),
                resolved.responses(),
                cancellation,
                eventConsumer,
                executor,
                options.metadata(),
                streaming);
        FunctionalRunContext context = new FunctionalRunContext(shared, null);
        CheckpointCoordinator checkpoints = resolved.checkpoints();
        if (checkpoints != null) {
            shared.stepCheckpointSaver(state -> checkpoints
                    .saveAsync(state, WorkflowCheckpointStatus.RUNNING)
                    .thenApply(ignored -> null));
        }
        if (request.mode() != ExecutionMode.CONTINUATION) {
            continuation.set(null);
        }

        shared.emit(
                WorkflowEventType.RUN_STARTED,
                null,
                null,
                StateValue.object(Map.of("workflowId", StateValue.string(id))));
        if (resolved.loadedCheckpoint() != null) {
            WorkflowCheckpoint loaded = resolved.loadedCheckpoint();
            shared.emit(
                    WorkflowEventType.CHECKPOINT_LOADED,
                    null,
                    null,
                    StateValue.object(Map.of(
                            "checkpointId",
                            StateValue.string(loaded.checkpointId()),
                            "revision",
                            StateValue.integer(loaded.revision()))));
        }
        if (request.mode() != ExecutionMode.FRESH) {
            shared.emit(
                    WorkflowEventType.WORKFLOW_RESUMED,
                    null,
                    null,
                    StateValue.object(Map.of(
                            "checkpointId",
                            resolved.loadedCheckpoint() == null
                                    ? StateValue.nullValue()
                                    : StateValue.string(
                                            resolved.loadedCheckpoint().checkpointId()))));
        }

        try {
            CompletionStage<O> bodyStage =
                    Objects.requireNonNull(function.execute(resolved.input(), context), "workflow stage");
            O output = outputType.cast(awaitCancellable(bodyStage, cancellation));
            if (!shared.pendingRequests().isEmpty()) {
                throw new WorkflowException(
                        "Functional workflow returned while previously pending input requests remain unresolved.");
            }
            if (output != null) {
                EncodedState encodedOutput = FunctionalStateCodecSupport.encode(outputCodec, output);
                shared.emit(
                        WorkflowEventType.OUTPUT,
                        null,
                        null,
                        StateValue.object(Map.of("value", encodedOutput.value())));
            }
            if (checkpoints != null) {
                awaitCancellable(checkpoints.saveAsync(shared, WorkflowCheckpointStatus.COMPLETED), cancellation);
            }
            shared.emit(
                    WorkflowEventType.RUN_COMPLETED,
                    null,
                    null,
                    StateValue.object(Map.of("status", StateValue.string("completed"))));
            continuation.set(null);
            return result(shared, FunctionalWorkflowRunStatus.COMPLETED, output, checkpoints);
        } catch (Throwable failure) {
            Throwable cause = RunHandles.unwrap(failure);
            if (cause instanceof FunctionalWorkflowInterrupted interrupted) {
                try {
                    if (checkpoints != null) {
                        awaitCancellable(
                                checkpoints.saveAsync(shared, WorkflowCheckpointStatus.INPUT_REQUIRED), cancellation);
                    }
                } catch (Throwable checkpointFailure) {
                    Throwable checkpointCause = RunHandles.unwrap(checkpointFailure);
                    if (checkpointCause instanceof RunCancelledException || cancellation.isCancellationRequested()) {
                        shared.emit(
                                WorkflowEventType.RUN_CANCELLED,
                                null,
                                null,
                                StateValue.object(Map.of("status", StateValue.string("cancelled"))));
                        throw checkpointCause instanceof RunCancelledException cancelled
                                ? cancelled
                                : new RunCancelledException();
                    }
                    shared.emit(WorkflowEventType.RUN_FAILED, null, null, errorData(checkpointCause));
                    if (checkpointCause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (checkpointCause instanceof Error error) {
                        throw error;
                    }
                    throw new WorkflowCheckpointException(
                            "Failed to save an input-required functional workflow checkpoint.", checkpointCause);
                }
                shared.emit(
                        WorkflowEventType.RUN_INTERRUPTED,
                        null,
                        null,
                        StateValue.object(Map.of(
                                "requestId",
                                StateValue.string(interrupted.request().requestId()),
                                "status",
                                StateValue.string("inputRequired"))));
                continuation.set(new Continuation(
                        resolved.runId(),
                        shared.snapshot(),
                        checkpoints == null ? null : checkpoints.storage,
                        checkpoints == null ? null : checkpoints.key,
                        checkpoints == null ? 0 : checkpoints.expectedRevision,
                        checkpoints == null ? null : checkpoints.previousCheckpointId));
                return result(shared, FunctionalWorkflowRunStatus.INPUT_REQUIRED, null, checkpoints);
            }
            if (cause instanceof RunCancelledException || cancellation.isCancellationRequested()) {
                shared.emit(
                        WorkflowEventType.RUN_CANCELLED,
                        null,
                        null,
                        StateValue.object(Map.of("status", StateValue.string("cancelled"))));
                throw cause instanceof RunCancelledException cancelled ? cancelled : new RunCancelledException();
            }
            shared.emit(WorkflowEventType.RUN_FAILED, null, null, errorData(cause));
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new WorkflowException("Functional workflow '" + id + "' failed.", cause);
        } finally {
            shared.stopAcceptingEvents();
        }
    }

    private ResolvedExecution<I> resolve(ExecutionRequest<I> request, FunctionalWorkflowRunOptions options) {
        if (request.mode() == ExecutionMode.FRESH) {
            String runId = options.runId() == null ? UUID.randomUUID().toString() : options.runId();
            EncodedState input = FunctionalStateCodecSupport.encode(inputCodec, request.input());
            FunctionalWorkflowSnapshot snapshot =
                    new FunctionalWorkflowSnapshot(input, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0);
            CheckpointCoordinator checkpoints = freshCheckpoints(options, runId);
            return new ResolvedExecution<>(
                    request.input(), runId, snapshot, FunctionalWorkflowResponses.empty(), checkpoints, null);
        }
        if (request.mode() == ExecutionMode.CONTINUATION) {
            Continuation saved = Objects.requireNonNull(request.continuation(), "continuation");
            if (continuation.get() != saved) {
                throw new WorkflowException(
                        "Functional workflow '" + id + "' continuation is stale and cannot be replayed.");
            }
            I input = FunctionalStateCodecSupport.decode(
                    "Functional workflow input",
                    inputType,
                    inputCodec,
                    saved.snapshot().originalInput());
            CheckpointCoordinator checkpoints = saved.storage() == null
                    ? null
                    : new CheckpointCoordinator(
                            id,
                            fingerprint,
                            saved.storage(),
                            saved.key(),
                            saved.runId(),
                            saved.expectedRevision(),
                            saved.previousCheckpointId());
            return new ResolvedExecution<>(
                    input, saved.runId(), saved.snapshot(), request.responses(), checkpoints, null);
        }

        VersionedSnapshot<WorkflowCheckpoint> versioned = request.storage()
                .loadAsync(request.key())
                .toCompletableFuture()
                .join()
                .orElseThrow(
                        () -> new WorkflowCheckpointException("Checkpoint '" + request.key() + "' was not found."));
        WorkflowCheckpoint checkpoint = versioned.snapshot();
        validateCheckpoint(checkpoint, versioned.revision());
        FunctionalWorkflowSnapshot snapshot = FunctionalWorkflowSnapshot.fromWorkflowState(checkpoint.state());
        if (checkpoint.superstep() != snapshot.completedLiveSteps()) {
            throw new WorkflowCheckpointException(
                    "Functional checkpoint completed-step count does not match its runtime state.");
        }
        I input = FunctionalStateCodecSupport.decode(
                "Functional workflow input", inputType, inputCodec, snapshot.originalInput());
        CheckpointCoordinator checkpoints = new CheckpointCoordinator(
                id,
                fingerprint,
                request.storage(),
                request.key(),
                checkpoint.runId(),
                versioned.revision(),
                checkpoint.checkpointId());
        return new ResolvedExecution<>(
                input, checkpoint.runId(), snapshot, request.responses(), checkpoints, checkpoint);
    }

    private CheckpointCoordinator freshCheckpoints(FunctionalWorkflowRunOptions options, String runId) {
        if (options.checkpointStorage() != null) {
            return new CheckpointCoordinator(
                    id,
                    fingerprint,
                    options.checkpointStorage(),
                    options.checkpointKey(),
                    runId,
                    options.expectedCheckpointRevision(),
                    null);
        }
        if (defaultCheckpointStorage == null) {
            return null;
        }
        return new CheckpointCoordinator(
                id,
                fingerprint,
                defaultCheckpointStorage,
                new CheckpointKey(runId),
                runId,
                CheckpointStorage.CREATE_ONLY,
                null);
    }

    private void validateCheckpoint(WorkflowCheckpoint checkpoint, long storageRevision) {
        if (!id.equals(checkpoint.workflowId())) {
            throw new WorkflowCheckpointException(
                    "Checkpoint belongs to workflow '" + checkpoint.workflowId() + "', not '" + id + "'.");
        }
        if (!checkpoint.isRuntimeCheckpoint()) {
            throw new WorkflowCheckpointException("Functional workflow resume requires a runtime checkpoint.");
        }
        if (checkpoint.workflowSchemaVersion() != SCHEMA_VERSION) {
            throw new WorkflowCheckpointException(
                    "Unsupported functional workflow schema version " + checkpoint.workflowSchemaVersion() + ".");
        }
        if (!fingerprint.equals(checkpoint.graphFingerprint())) {
            throw new WorkflowCheckpointException(
                    "Checkpoint fingerprint does not match functional workflow '" + id + "'.");
        }
        if (checkpoint.revision() != storageRevision) {
            throw new WorkflowCheckpointException("Checkpoint payload revision does not match the storage revision.");
        }
        if (!checkpoint.pendingExecutors().isEmpty()
                || !checkpoint.bufferedInputs().isEmpty()
                || !checkpoint.fanInNextEpochs().isEmpty()) {
            throw new WorkflowCheckpointException(
                    "Functional workflow checkpoints cannot contain graph executor or fan-in state.");
        }
    }

    private FunctionalWorkflowRunResult<O> result(
            FunctionalRunContext.SharedState shared,
            FunctionalWorkflowRunStatus status,
            O output,
            CheckpointCoordinator checkpoints) {
        CheckpointReference reference = checkpoints == null ? null : checkpoints.reference();
        return new FunctionalWorkflowRunResult<>(
                shared.runId(),
                status,
                output,
                shared.events(),
                shared.pendingRequests(),
                reference == null ? null : reference.key(),
                reference == null ? null : reference.checkpointId(),
                reference == null ? 0 : reference.revision());
    }

    private static <T> T awaitCancellable(CompletionStage<T> stage, RunCancellation cancellation) {
        CompletableFuture<T> race = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            if (failure == null) {
                race.complete(value);
            } else {
                race.completeExceptionally(RunHandles.unwrap(failure));
            }
        });
        RunCancellationRegistration registration =
                RunCancellations.register(cancellation, () -> race.completeExceptionally(new RunCancelledException()));
        try {
            return race.join();
        } finally {
            registration.close();
        }
    }

    private static StateValue errorData(Throwable failure) {
        return StateValue.object(Map.of(
                "errorType",
                StateValue.string(failure.getClass().getSimpleName()),
                "message",
                StateValue.string(failure.getMessage() == null ? "" : failure.getMessage())));
    }

    private static void cancellationCheck(RunCancellation cancellation) {
        if (cancellation.isCancellationRequested()) {
            throw new RunCancelledException();
        }
    }

    private static void requireNoCheckpointOverride(FunctionalWorkflowRunOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.checkpointStorage() != null) {
            throw new WorkflowValidationException(
                    "Resume options cannot replace the checkpoint storage or key selected by the continuation.");
        }
        if (options.runId() != null) {
            throw new WorkflowValidationException("Resume options cannot replace the logical run identifier.");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new WorkflowException("Functional workflow '" + id + "' is closed.");
        }
    }

    private static String fingerprint(
            String id,
            Class<?> inputType,
            Class<?> outputType,
            StateCodec<?> inputCodec,
            StateCodec<?> outputCodec,
            String signatureVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeFingerprintValue(output, id);
                writeFingerprintValue(output, inputType.getName());
                writeFingerprintValue(output, outputType.getName());
                writeFingerprintValue(output, inputCodec.typeId());
                output.writeInt(inputCodec.currentVersion());
                writeFingerprintValue(output, outputCodec.typeId());
                output.writeInt(outputCodec.currentVersion());
                writeFingerprintValue(output, signatureVersion);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory fingerprint encoding failure.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void writeFingerprintValue(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /**
     * Builds an immutable functional workflow.
     *
     * @param <I> workflow input type
     * @param <O> workflow output type
     */
    public static final class Builder<I, O> {
        private final String id;

        private final Class<I> inputType;

        private final Class<O> outputType;

        private final StateCodec<I> inputCodec;

        private final StateCodec<O> outputCodec;

        private String description;

        private String signatureVersion = "1";

        private FunctionalWorkflowFunction<I, O> function;

        private CheckpointStorage defaultCheckpointStorage;

        private Builder(
                String id,
                Class<I> inputType,
                Class<O> outputType,
                StateCodec<I> inputCodec,
                StateCodec<O> outputCodec) {
            this.id = WorkflowValidation.requireNonBlank(id, "workflow id");
            this.inputType = Objects.requireNonNull(inputType, "inputType");
            this.outputType = Objects.requireNonNull(outputType, "outputType");
            this.inputCodec = Objects.requireNonNull(inputCodec, "inputCodec");
            this.outputCodec = Objects.requireNonNull(outputCodec, "outputCodec");
            WorkflowValidation.requireCodec(inputCodec);
            WorkflowValidation.requireCodec(outputCodec);
        }

        /**
         * Sets the optional workflow description.
         *
         * @param description workflow description
         * @return this builder
         */
        public Builder<I, O> description(String description) {
            this.description = WorkflowValidation.requireNonBlank(description, "description");
            return this;
        }

        /**
         * Sets the application-managed signature version included in checkpoint fingerprints.
         *
         * <p>Increment this value whenever workflow control flow changes incompatibly.
         *
         * @param signatureVersion stable application version
         * @return this builder
         */
        public Builder<I, O> signatureVersion(String signatureVersion) {
            this.signatureVersion = WorkflowValidation.requireNonBlank(signatureVersion, "signatureVersion");
            return this;
        }

        /**
         * Sets the workflow body.
         *
         * @param function functional workflow body
         * @return this builder
         */
        public Builder<I, O> body(FunctionalWorkflowFunction<I, O> function) {
            this.function = Objects.requireNonNull(function, "function");
            return this;
        }

        /**
         * Sets default checkpoint storage for fresh runs.
         *
         * <p>Fresh runs use their generated or caller-supplied run identifier as the checkpoint
         * key. Per-run options can override this storage and key together.
         *
         * @param storage default checkpoint storage
         * @return this builder
         */
        public Builder<I, O> checkpointStorage(CheckpointStorage storage) {
            defaultCheckpointStorage = Objects.requireNonNull(storage, "storage");
            return this;
        }

        /**
         * Creates the functional workflow.
         *
         * @return functional workflow
         */
        public FunctionalWorkflow<I, O> build() {
            if (function == null) {
                throw new IllegalStateException("Functional workflow body must be configured.");
            }
            FeatureUsageIndexes.markCoreWorkflowUsed();
            return new FunctionalWorkflow<>(this);
        }
    }

    private static final class CheckpointCoordinator {
        private final String workflowId;

        private final String fingerprint;

        private final CheckpointStorage storage;

        private final CheckpointKey key;

        private final String runId;

        private CompletionStage<CheckpointReference> chain = CompletableFuture.completedFuture(null);

        private long expectedRevision;

        private String previousCheckpointId;

        private CheckpointCoordinator(
                String workflowId,
                String fingerprint,
                CheckpointStorage storage,
                CheckpointKey key,
                String runId,
                long expectedRevision,
                String previousCheckpointId) {
            this.workflowId = WorkflowValidation.requireNonBlank(workflowId, "workflowId");
            this.fingerprint = WorkflowValidation.requireNonBlank(fingerprint, "fingerprint");
            this.storage = Objects.requireNonNull(storage, "storage");
            this.key = Objects.requireNonNull(key, "key");
            this.runId = WorkflowValidation.requireNonBlank(runId, "runId");
            if (expectedRevision != CheckpointStorage.CREATE_ONLY && expectedRevision <= 0) {
                throw new WorkflowValidationException(
                        "expectedRevision must be -1 for create-only or greater than zero.");
            }
            this.expectedRevision = expectedRevision;
            this.previousCheckpointId = previousCheckpointId;
        }

        private synchronized CompletionStage<CheckpointReference> saveAsync(
                FunctionalRunContext.SharedState shared, WorkflowCheckpointStatus status) {
            int ordinal = shared.incrementCheckpointOrdinal();
            FunctionalWorkflowSnapshot snapshot = shared.snapshot();
            int completedLiveSteps = shared.completedLiveSteps();
            String checkpointId = runId + "-functional-checkpoint-" + ordinal;
            chain = chain.thenCompose(ignored -> {
                WorkflowCheckpoint draft = new WorkflowCheckpoint(
                        workflowId,
                        checkpointId,
                        0,
                        previousCheckpointId,
                        status,
                        List.of(),
                        List.of(),
                        Map.of(),
                        SCHEMA_VERSION,
                        fingerprint,
                        runId,
                        completedLiveSteps,
                        snapshot.toWorkflowState());
                return storage.saveAsync(key, draft, expectedRevision).thenApply(saved -> {
                    expectedRevision = saved.revision();
                    previousCheckpointId = saved.snapshot().checkpointId();
                    shared.emit(
                            WorkflowEventType.CHECKPOINT_SAVED,
                            null,
                            null,
                            StateValue.object(Map.of(
                                    "checkpointId",
                                    StateValue.string(saved.snapshot().checkpointId()),
                                    "revision",
                                    StateValue.integer(saved.revision()),
                                    "previousCheckpointId",
                                    saved.snapshot().previousCheckpointId() == null
                                            ? StateValue.nullValue()
                                            : StateValue.string(saved.snapshot().previousCheckpointId()))));
                    return new CheckpointReference(key, saved.snapshot().checkpointId(), saved.revision());
                });
            });
            return chain;
        }

        private synchronized CheckpointReference reference() {
            if (expectedRevision <= 0 || previousCheckpointId == null) {
                return null;
            }
            return new CheckpointReference(key, previousCheckpointId, expectedRevision);
        }
    }

    private enum ExecutionMode {
        FRESH,
        CONTINUATION,
        CHECKPOINT
    }

    private record ExecutionRequest<I>(
            ExecutionMode mode,
            I input,
            Continuation continuation,
            CheckpointStorage storage,
            CheckpointKey key,
            FunctionalWorkflowResponses responses) {
        private static <I> ExecutionRequest<I> fresh(I input) {
            return new ExecutionRequest<>(
                    ExecutionMode.FRESH, input, null, null, null, FunctionalWorkflowResponses.empty());
        }

        private static <I> ExecutionRequest<I> continuation(
                Continuation continuation, FunctionalWorkflowResponses responses) {
            return new ExecutionRequest<>(ExecutionMode.CONTINUATION, null, continuation, null, null, responses);
        }

        private static <I> ExecutionRequest<I> checkpoint(
                CheckpointStorage storage, CheckpointKey key, FunctionalWorkflowResponses responses) {
            return new ExecutionRequest<>(ExecutionMode.CHECKPOINT, null, null, storage, key, responses);
        }
    }

    private record ResolvedExecution<I>(
            I input,
            String runId,
            FunctionalWorkflowSnapshot snapshot,
            FunctionalWorkflowResponses responses,
            CheckpointCoordinator checkpoints,
            WorkflowCheckpoint loadedCheckpoint) {}

    private record Continuation(
            String runId,
            FunctionalWorkflowSnapshot snapshot,
            CheckpointStorage storage,
            CheckpointKey key,
            long expectedRevision,
            String previousCheckpointId) {}

    private record CheckpointReference(CheckpointKey key, String checkpointId, long revision) {}
}
