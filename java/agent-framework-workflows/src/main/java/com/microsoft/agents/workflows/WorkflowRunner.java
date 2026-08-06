// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancellationRegistration;
import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.VersionedSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class WorkflowRunner<I, O> {
    private static final Comparator<PendingInvocation> INVOCATION_ORDER =
            Comparator.comparing(PendingInvocation::nodeId).thenComparingLong(PendingInvocation::ordinal);

    private final Workflow<I, O> workflow;

    private final RunCancellation cancellation;

    private final WorkflowRunOptions options;

    private final ExecutorService executor;

    private final Consumer<WorkflowEvent> events;

    private final I initialInput;

    private final CheckpointStorage resumeStorage;

    private final CheckpointKey resumeKey;

    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    private final AtomicLong eventSequence = new AtomicLong();

    private final AtomicLong invocationSequence = new AtomicLong();

    private final Object branchLock = new Object();

    private final ArrayList<BranchTask> inFlight = new ArrayList<>();

    private final Map<FanInEdgeGroup, LinkedHashMap<NodeId, Object>> fanInBuffers = new HashMap<>();

    private final Map<FanInEdgeGroup, Long> fanInEpochs = new HashMap<>();

    private String runId;

    private WorkflowState state;

    private List<PendingInvocation> pending;

    private int superstep;

    private O terminalOutput;

    private Long checkpointRevision;

    private long expectedCheckpointRevision;

    private String previousCheckpointId;

    private CheckpointStorage checkpointStorage;

    private CheckpointKey checkpointKey;

    private boolean terminalEmitted;

    private WorkflowRunner(
            Workflow<I, O> workflow,
            RunCancellation cancellation,
            WorkflowRunOptions options,
            I initialInput,
            CheckpointStorage resumeStorage,
            CheckpointKey resumeKey,
            ExecutorService executor,
            Consumer<WorkflowEvent> events) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.options = Objects.requireNonNull(options, "options");
        this.initialInput = initialInput;
        this.resumeStorage = resumeStorage;
        this.resumeKey = resumeKey;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.events = Objects.requireNonNull(events, "events");
    }

    static <I, O> WorkflowRunner<I, O> fresh(
            Workflow<I, O> workflow,
            RunCancellation cancellation,
            WorkflowRunOptions options,
            I input,
            ExecutorService executor,
            Consumer<WorkflowEvent> events) {
        return new WorkflowRunner<>(
                workflow, cancellation, options, Objects.requireNonNull(input, "input"), null, null, executor, events);
    }

    static <I, O> WorkflowRunner<I, O> resume(
            Workflow<I, O> workflow,
            RunCancellation cancellation,
            WorkflowRunOptions options,
            CheckpointStorage storage,
            CheckpointKey key,
            ExecutorService executor,
            Consumer<WorkflowEvent> events) {
        return new WorkflowRunner<>(
                workflow,
                cancellation,
                options,
                null,
                Objects.requireNonNull(storage, "storage"),
                Objects.requireNonNull(key, "key"),
                executor,
                events);
    }

    WorkflowRunResult<O> run() {
        try {
            runId = workflow.newRunId(options);
            if (resumeStorage == null) {
                initializeFresh();
            } else {
                initializeResume();
            }
            while (!pending.isEmpty() && superstep < options.maxSupersteps()) {
                requireNotCancelled();
                runSuperstep();
            }
            requireNotCancelled();
            if (!pending.isEmpty()) {
                throw new WorkflowConvergenceException("Workflow '" + workflow.id() + "' did not converge after "
                        + options.maxSupersteps() + " supersteps.");
            }
            if (!fanInBuffers.isEmpty()) {
                throw new WorkflowConvergenceException(
                        "Workflow '" + workflow.id() + "' stopped with an incomplete fan-in epoch.");
            }
            if (terminalOutput == null) {
                throw new WorkflowConvergenceException("Workflow '" + workflow.id()
                        + "' converged without output from node '" + workflow.outputNodeId() + "'.");
            }
            emitTerminal(WorkflowEventType.RUN_COMPLETED, StateValue.string("idle"));
            return new WorkflowRunResult<>(runId, terminalOutput, state, superstep, checkpointRevision);
        } catch (RunCancelledException cancelled) {
            emitTerminal(WorkflowEventType.RUN_CANCELLED, StateValue.string("cancelled"));
            throw cancelled;
        } catch (Throwable failure) {
            Throwable cause = RunHandles.unwrap(failure);
            emitTerminal(
                    WorkflowEventType.RUN_FAILED,
                    StateValue.object(Map.of(
                            "outcome",
                            StateValue.string("failed"),
                            "errorType",
                            StateValue.string(cause.getClass().getSimpleName()))));
            if (cause instanceof WorkflowException workflowFailure) {
                throw workflowFailure;
            }
            throw new WorkflowException("Workflow '" + workflow.id() + "' failed.", cause);
        } finally {
            cancelBranches();
        }
    }

    void cancel() {
        cancelRequested.set(true);
        cancelBranches();
    }

    private void initializeFresh() {
        state = options.initialState();
        superstep = 0;
        pending = List.of(new PendingInvocation(
                workflow.entryNodeId(), initialInput, correlation(invocationSequence.getAndIncrement()), 0));
        checkpointStorage = options.checkpointStorage();
        checkpointKey = options.checkpointKey();
        expectedCheckpointRevision = options.expectedCheckpointRevision();
        emit(
                WorkflowEventType.RUN_STARTED,
                null,
                -1,
                null,
                StateValue.object(Map.of("workflowId", StateValue.string(workflow.id()))));
    }

    private void initializeResume() {
        VersionedSnapshot<WorkflowCheckpoint> loaded = resumeStorage
                .loadAsync(resumeKey)
                .toCompletableFuture()
                .join()
                .orElseThrow(() -> new WorkflowCheckpointException("Checkpoint '" + resumeKey + "' was not found."));
        WorkflowCheckpoint checkpoint = loaded.snapshot();
        validateCheckpoint(checkpoint, loaded.revision());
        runId = checkpoint.runId();
        state = checkpoint.state();
        superstep = checkpoint.superstep();
        checkpointStorage = resumeStorage;
        checkpointKey = resumeKey;
        expectedCheckpointRevision = loaded.revision();
        checkpointRevision = loaded.revision();
        previousCheckpointId = checkpoint.checkpointId();
        restoreFanInEpochs(checkpoint);
        restorePending(checkpoint);
        emit(
                WorkflowEventType.CHECKPOINT_LOADED,
                null,
                -1,
                null,
                StateValue.object(Map.of(
                        "checkpointId",
                        StateValue.string(checkpoint.checkpointId()),
                        "revision",
                        StateValue.integer(loaded.revision()))));
        emit(
                WorkflowEventType.WORKFLOW_RESUMED,
                null,
                -1,
                null,
                StateValue.object(Map.of("checkpointId", StateValue.string(checkpoint.checkpointId()))));
    }

    private void validateCheckpoint(WorkflowCheckpoint checkpoint, long storageRevision) {
        if (!checkpoint.isRuntimeCheckpoint()) {
            throw new WorkflowCheckpointException(
                    "Portable fixture checkpoints do not contain runtime graph identity and cannot be resumed.");
        }
        if (!workflow.id().equals(checkpoint.workflowId())) {
            throw new WorkflowCheckpointException("Checkpoint workflow identity does not match this workflow.");
        }
        if (workflow.schemaVersion() != checkpoint.workflowSchemaVersion()) {
            throw new WorkflowCheckpointException("Checkpoint workflow schema version does not match.");
        }
        if (!workflow.graphFingerprint().equals(checkpoint.graphFingerprint())) {
            throw new WorkflowCheckpointException("Checkpoint graph fingerprint does not match.");
        }
        if (checkpoint.revision() != storageRevision) {
            throw new WorkflowCheckpointException("Checkpoint payload revision does not match storage revision.");
        }
        if (options.runId() != null && !options.runId().equals(checkpoint.runId())) {
            throw new WorkflowCheckpointException("Requested runId does not match the checkpoint runId.");
        }
        if (checkpoint.status() == WorkflowCheckpointStatus.COMPLETED) {
            throw new WorkflowCheckpointException("A completed checkpoint has no pending work to resume.");
        }
    }

    private void restorePending(WorkflowCheckpoint checkpoint) {
        Map<NodeId, List<BufferedInput>> byTarget = new TreeMap<>();
        checkpoint.bufferedInputs().forEach(input -> byTarget.computeIfAbsent(
                        input.targetId(), ignored -> new ArrayList<>())
                .add(input));
        ArrayList<PendingInvocation> restored = new ArrayList<>();
        for (NodeId nodeId : checkpoint.pendingExecutors()) {
            WorkflowNode<?, ?> target = requireNode(nodeId);
            List<BufferedInput> inputs = byTarget.remove(nodeId);
            if (inputs == null || inputs.isEmpty()) {
                throw new WorkflowCheckpointException("Pending executor '" + nodeId + "' has no checkpointed input.");
            }
            List<BufferedInput> normalInputs = inputs.stream()
                    .filter(input -> BufferedInput.isPendingSource(input.sourceId()))
                    .toList();
            if (!normalInputs.isEmpty()) {
                if (normalInputs.size() != inputs.size()) {
                    throw new WorkflowCheckpointException(
                            "Pending executor '" + nodeId + "' mixes direct and fan-in checkpoint values.");
                }
                for (BufferedInput buffered : normalInputs) {
                    long ordinal = invocationSequence.getAndIncrement();
                    String restoredCorrelation = BufferedInput.pendingCorrelation(buffered.sourceId());
                    restored.add(new PendingInvocation(
                            nodeId,
                            target.decodeInput(EncodedState.fromStateValue(buffered.value())),
                            restoredCorrelation == null ? correlation(ordinal) : restoredCorrelation,
                            ordinal));
                }
            } else {
                if (!FanInInput.class.equals(target.inputType())) {
                    throw new WorkflowCheckpointException(
                            "Pending executor '" + nodeId + "' has fan-in values but does not accept FanInInput.");
                }
                LinkedHashMap<NodeId, Object> values = new LinkedHashMap<>();
                for (BufferedInput buffered : inputs) {
                    NodeId sourceId = new NodeId(buffered.sourceId());
                    values.put(
                            sourceId,
                            requireNode(sourceId).decodeOutput(EncodedState.fromStateValue(buffered.value())));
                }
                long ordinal = invocationSequence.getAndIncrement();
                FanInEdgeGroup group = findFanIn(nodeId);
                long nextEpoch = fanInEpochs.getOrDefault(group, 0L);
                if (nextEpoch == 0) {
                    throw new WorkflowCheckpointException("Pending fan-in target '" + nodeId
                            + "' requires a positive next epoch because its input was already released.");
                }
                restored.add(new PendingInvocation(
                        nodeId, new FanInInput(nextEpoch - 1, values), correlation(ordinal), ordinal));
            }
        }
        for (Map.Entry<NodeId, List<BufferedInput>> entry : byTarget.entrySet()) {
            FanInEdgeGroup group = findFanIn(entry.getKey());
            LinkedHashMap<NodeId, Object> buffer =
                    fanInBuffers.computeIfAbsent(group, ignored -> new LinkedHashMap<>());
            for (BufferedInput buffered : entry.getValue()) {
                NodeId sourceId = new NodeId(buffered.sourceId());
                buffer.put(sourceId, requireNode(sourceId).decodeOutput(EncodedState.fromStateValue(buffered.value())));
            }
        }
        restored.sort(INVOCATION_ORDER);
        pending = List.copyOf(restored);
    }

    private void restoreFanInEpochs(WorkflowCheckpoint checkpoint) {
        checkpoint.fanInNextEpochs().forEach((targetId, nextEpoch) -> {
            FanInEdgeGroup group = findFanIn(targetId);
            fanInEpochs.put(group, nextEpoch);
        });
    }

    private void runSuperstep() {
        ArrayList<PendingInvocation> current = new ArrayList<>(pending);
        current.sort(INVOCATION_ORDER);
        emit(
                WorkflowEventType.SUPERSTEP_STARTED,
                null,
                superstep,
                null,
                StateValue.object(Map.of("pendingCount", StateValue.integer(current.size()))));
        for (PendingInvocation invocation : current) {
            emit(
                    WorkflowEventType.NODE_STARTED,
                    invocation.nodeId(),
                    superstep,
                    invocation.correlationId(),
                    encodeEventValue(invocation.input()));
        }

        List<BranchTask> tasks = startBranches(current);
        CompletableFuture.allOf(tasks.stream().map(BranchTask::outcome).toArray(CompletableFuture[]::new))
                .join();
        List<BranchOutcome> outcomes = tasks.stream()
                .map(task -> task.outcome().join())
                .sorted(Comparator.comparing(outcome -> outcome.invocation(), INVOCATION_ORDER))
                .toList();
        clearBranches(tasks);
        requireNotCancelled();

        BranchOutcome failed = outcomes.stream()
                .filter(outcome -> outcome.failure() != null && !outcome.cancelled())
                .findFirst()
                .orElse(null);
        if (failed != null) {
            WorkflowExecutionException executionFailure =
                    new WorkflowExecutionException(failed.invocation().nodeId(), superstep, failed.failure());
            emit(
                    WorkflowEventType.NODE_FAILED,
                    failed.invocation().nodeId(),
                    superstep,
                    failed.invocation().correlationId(),
                    StateValue.object(Map.of(
                            "errorType",
                            StateValue.string(executionFailure.getClass().getSimpleName()))));
            outcomes.stream()
                    .filter(outcome -> outcome != failed && outcome.cancelled())
                    .forEach(this::emitCancelled);
            throw executionFailure;
        }
        BranchOutcome unexpectedCancellation =
                outcomes.stream().filter(BranchOutcome::cancelled).findFirst().orElse(null);
        if (unexpectedCancellation != null) {
            throw new RunCancelledException();
        }

        for (BranchOutcome outcome : outcomes) {
            if (outcome.invocation().nodeId().equals(workflow.outputNodeId())) {
                terminalOutput = workflow.outputType().cast(outcome.output());
                emit(
                        WorkflowEventType.OUTPUT,
                        outcome.invocation().nodeId(),
                        superstep,
                        outcome.invocation().correlationId(),
                        encodeEventValue(outcome.output()));
            }
            emit(
                    WorkflowEventType.NODE_COMPLETED,
                    outcome.invocation().nodeId(),
                    superstep,
                    outcome.invocation().correlationId(),
                    encodeEventValue(outcome.output()));
        }

        commitState(outcomes);
        pending = route(outcomes);
        superstep++;
        saveCheckpointIfEnabled();
        emit(
                WorkflowEventType.SUPERSTEP_COMPLETED,
                null,
                superstep - 1,
                null,
                StateValue.object(Map.of("nextPendingCount", StateValue.integer(pending.size()))));
    }

    private List<BranchTask> startBranches(List<PendingInvocation> invocations) {
        ArrayList<BranchTask> tasks = new ArrayList<>(invocations.size());
        synchronized (branchLock) {
            inFlight.clear();
            for (PendingInvocation invocation : invocations) {
                DefaultRunCancellation branchCancellation = new DefaultRunCancellation();
                RunCancellationRegistration parentRegistration =
                        RunCancellations.register(cancellation, branchCancellation::cancel);
                WorkflowContext context = new WorkflowContext(
                        runId,
                        invocation.nodeId(),
                        superstep,
                        invocation.correlationId(),
                        state,
                        branchCancellation,
                        options.metadata());
                WorkflowNode<?, ?> node = requireNode(invocation.nodeId());
                CompletableFuture<Object> execution = CompletableFuture.supplyAsync(
                                () -> node.execute(invocation.input(), context), executor)
                        .thenCompose(stage -> stage);
                CompletableFuture<BranchOutcome> outcome = execution.handle((output, failure) -> {
                    Throwable cause = failure == null ? null : RunHandles.unwrap(failure);
                    boolean cancelled = cause instanceof java.util.concurrent.CancellationException
                            || cause instanceof RunCancelledException
                            || branchCancellation.isCancellationRequested();
                    return new BranchOutcome(invocation, output, context, cause, cancelled);
                });
                BranchTask task =
                        new BranchTask(invocation, branchCancellation, parentRegistration, execution, outcome);
                tasks.add(task);
                inFlight.add(task);
            }
            for (BranchTask task : tasks) {
                task.execution().whenComplete((output, failure) -> {
                    if (failure != null
                            && !(RunHandles.unwrap(failure) instanceof java.util.concurrent.CancellationException)) {
                        cancelOtherBranches(task);
                    }
                });
            }
        }
        return List.copyOf(tasks);
    }

    private void cancelOtherBranches(BranchTask failing) {
        synchronized (branchLock) {
            for (BranchTask task : inFlight) {
                if (task != failing && !task.execution().isDone()) {
                    task.cancellation().cancel();
                    task.execution().cancel(true);
                }
            }
        }
    }

    private void cancelBranches() {
        synchronized (branchLock) {
            for (BranchTask task : inFlight) {
                task.cancellation().cancel();
                task.execution().cancel(true);
            }
        }
    }

    private void clearBranches(List<BranchTask> tasks) {
        synchronized (branchLock) {
            tasks.forEach(task -> task.parentRegistration().close());
            inFlight.clear();
        }
    }

    private void emitCancelled(BranchOutcome outcome) {
        emit(
                WorkflowEventType.CANCELLATION_REQUESTED,
                outcome.invocation().nodeId(),
                superstep,
                outcome.invocation().correlationId(),
                StateValue.nullValue());
        emit(
                WorkflowEventType.NODE_CANCELLED,
                outcome.invocation().nodeId(),
                superstep,
                outcome.invocation().correlationId(),
                StateValue.nullValue());
    }

    private void commitState(List<BranchOutcome> outcomes) {
        TreeMap<String, List<StateMutation>> grouped = new TreeMap<>();
        for (BranchOutcome outcome : outcomes) {
            outcome.context().mutations().values().forEach(mutation -> grouped.computeIfAbsent(
                            mutation.key().name(), ignored -> new ArrayList<>())
                    .add(mutation));
        }
        LinkedHashMap<String, EncodedState> replacements = new LinkedHashMap<>();
        grouped.forEach((name, mutations) -> {
            StateKey<?> key = mutations.getFirst().key();
            if (mutations.stream().anyMatch(mutation -> !key.equals(mutation.key()))) {
                throw new StateConflictException(name);
            }
            replacements.put(
                    name,
                    key.mergeEncoded(
                            mutations.stream().map(StateMutation::value).toList()));
        });
        if (!replacements.isEmpty()) {
            state = state.with(replacements);
            emit(
                    WorkflowEventType.STATE_COMMITTED,
                    null,
                    superstep,
                    null,
                    StateValue.array(replacements.keySet().stream()
                            .map(StateValue::string)
                            .toList()));
        }
    }

    private List<PendingInvocation> route(List<BranchOutcome> outcomes) {
        ArrayList<PendingInvocation> next = new ArrayList<>();
        for (BranchOutcome outcome : outcomes) {
            NodeId sourceId = outcome.invocation().nodeId();
            Object output = outcome.output();
            for (Edge edge : workflow.edges()) {
                if (!edge.sourceId().equals(sourceId)) {
                    continue;
                }
                if (edge instanceof ConditionalEdge<?> conditional && !conditional.matches(output)) {
                    continue;
                }
                next.add(invocation(edge.targetId(), output));
            }
            for (EdgeGroup group : workflow.edgeGroups()) {
                if (group instanceof FanOutEdgeGroup fanOut && fanOut.sourceId().equals(sourceId)) {
                    emit(
                            WorkflowEventType.FAN_OUT,
                            sourceId,
                            superstep,
                            outcome.invocation().correlationId(),
                            StateValue.object(Map.of(
                                    "targetIds",
                                    StateValue.array(fanOut.targetIds().stream()
                                            .map(target -> StateValue.string(target.value()))
                                            .toList()),
                                    "value",
                                    encodeEventValue(output))));
                    fanOut.targetIds().forEach(target -> next.add(invocation(target, output)));
                } else if (group instanceof FanInEdgeGroup fanIn
                        && fanIn.sourceIds().contains(sourceId)) {
                    bufferFanIn(fanIn, sourceId, output, outcome.invocation().correlationId(), next);
                }
            }
        }
        next.sort(INVOCATION_ORDER);
        return List.copyOf(next);
    }

    private void bufferFanIn(
            FanInEdgeGroup group, NodeId sourceId, Object output, String correlationId, List<PendingInvocation> next) {
        LinkedHashMap<NodeId, Object> buffer = fanInBuffers.computeIfAbsent(group, ignored -> new LinkedHashMap<>());
        if (buffer.putIfAbsent(sourceId, output) != null) {
            throw new WorkflowConvergenceException("Fan-in target '" + group.targetId() + "' received source '"
                    + sourceId + "' twice in the same epoch.");
        }
        emit(
                WorkflowEventType.FAN_IN_BUFFERED,
                sourceId,
                superstep,
                correlationId,
                StateValue.object(Map.of(
                        "targetId", StateValue.string(group.targetId().value()), "value", encodeEventValue(output))));
        if (!buffer.keySet().containsAll(group.sourceIds())) {
            return;
        }
        LinkedHashMap<NodeId, Object> ordered = new LinkedHashMap<>();
        group.sourceIds().forEach(id -> ordered.put(id, buffer.get(id)));
        long epoch = fanInEpochs.getOrDefault(group, 0L);
        fanInEpochs.put(group, epoch + 1);
        fanInBuffers.remove(group);
        emit(
                WorkflowEventType.FAN_IN_RELEASED,
                group.targetId(),
                superstep,
                correlationId,
                StateValue.object(Map.of(
                        "sourceIds",
                        StateValue.array(group.sourceIds().stream()
                                .map(id -> StateValue.string(id.value()))
                                .toList()),
                        "values",
                        StateValue.array(ordered.values().stream()
                                .map(this::encodeEventValue)
                                .toList()),
                        "epoch",
                        StateValue.integer(epoch))));
        next.add(invocation(group.targetId(), new FanInInput(epoch, ordered)));
    }

    private PendingInvocation invocation(NodeId targetId, Object input) {
        long ordinal = invocationSequence.getAndIncrement();
        return new PendingInvocation(targetId, input, correlation(ordinal), ordinal);
    }

    private void saveCheckpointIfEnabled() {
        if (checkpointStorage == null) {
            return;
        }
        requireNotCancelled();
        List<NodeId> pendingExecutors =
                pending.stream().map(PendingInvocation::nodeId).distinct().toList();
        List<BufferedInput> bufferedInputs = checkpointInputs();
        TreeMap<NodeId, Long> fanInNextEpochs = new TreeMap<>();
        fanInEpochs.forEach((group, nextEpoch) -> fanInNextEpochs.put(group.targetId(), nextEpoch));
        WorkflowCheckpointStatus status = pending.isEmpty() && fanInBuffers.isEmpty()
                ? WorkflowCheckpointStatus.COMPLETED
                : pending.isEmpty() ? WorkflowCheckpointStatus.INPUT_REQUIRED : WorkflowCheckpointStatus.RUNNING;
        String checkpointId = runId + "-checkpoint-" + superstep;
        WorkflowCheckpoint draft = new WorkflowCheckpoint(
                workflow.id(),
                checkpointId,
                0,
                previousCheckpointId,
                status,
                pendingExecutors,
                bufferedInputs,
                fanInNextEpochs,
                workflow.schemaVersion(),
                workflow.graphFingerprint(),
                runId,
                superstep,
                state);
        VersionedSnapshot<WorkflowCheckpoint> saved = checkpointStorage
                .saveAsync(checkpointKey, draft, expectedCheckpointRevision)
                .toCompletableFuture()
                .join();
        checkpointRevision = saved.revision();
        expectedCheckpointRevision = saved.revision();
        previousCheckpointId = saved.snapshot().checkpointId();
        emit(
                WorkflowEventType.CHECKPOINT_SAVED,
                null,
                superstep - 1,
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
    }

    private List<BufferedInput> checkpointInputs() {
        ArrayList<BufferedInput> result = new ArrayList<>();
        for (PendingInvocation invocation : pending) {
            if (invocation.input() instanceof FanInInput fanInInput) {
                for (Map.Entry<NodeId, Object> entry : fanInInput.rawValues().entrySet()) {
                    result.add(new BufferedInput(
                            invocation.nodeId(),
                            entry.getKey().value(),
                            requireNode(entry.getKey())
                                    .encodeOutput(entry.getValue())
                                    .toStateValue()));
                }
            } else {
                result.add(new BufferedInput(
                        invocation.nodeId(),
                        BufferedInput.pendingSource(invocation.correlationId()),
                        requireNode(invocation.nodeId())
                                .encodeInput(invocation.input())
                                .toStateValue()));
            }
        }
        fanInBuffers.forEach((group, values) -> values.forEach((sourceId, value) -> result.add(new BufferedInput(
                group.targetId(),
                sourceId.value(),
                requireNode(sourceId).encodeOutput(value).toStateValue()))));
        result.sort(BufferedInput::compareTo);
        return List.copyOf(result);
    }

    private FanInEdgeGroup findFanIn(NodeId targetId) {
        return workflow.edgeGroups().stream()
                .filter(FanInEdgeGroup.class::isInstance)
                .map(FanInEdgeGroup.class::cast)
                .filter(group -> group.targetId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new WorkflowCheckpointException(
                        "Checkpoint contains fan-in values for unknown target '" + targetId + "'."));
    }

    private WorkflowNode<?, ?> requireNode(NodeId nodeId) {
        WorkflowNode<?, ?> node = workflow.node(nodeId);
        if (node == null) {
            throw new WorkflowCheckpointException("Workflow node '" + nodeId + "' is missing.");
        }
        return node;
    }

    private StateValue encodeEventValue(Object value) {
        StateValue encoded = options.valueEncoder().encode(value);
        if (encoded == null) {
            throw new WorkflowValueEncodingException("Workflow value encoder returned null for type '"
                    + (value == null ? "null" : value.getClass().getName())
                    + "'.");
        }
        return encoded;
    }

    private String correlation(long ordinal) {
        return runId + ":" + ordinal;
    }

    private void requireNotCancelled() {
        if (cancelRequested.get()
                || cancellation.isCancellationRequested()
                || Thread.currentThread().isInterrupted()) {
            throw new RunCancelledException();
        }
    }

    private void emit(
            WorkflowEventType type, NodeId nodeId, int eventSuperstep, String correlationId, StateValue data) {
        events.accept(new WorkflowEvent(
                eventSequence.getAndIncrement(), type, runId, nodeId, eventSuperstep, correlationId, data));
    }

    private void emitTerminal(WorkflowEventType type, StateValue data) {
        if (!terminalEmitted) {
            terminalEmitted = true;
            emit(type, null, -1, null, data);
        }
    }

    private record PendingInvocation(NodeId nodeId, Object input, String correlationId, long ordinal) {
        PendingInvocation {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(input, "input");
            WorkflowValidation.requireNonBlank(correlationId, "correlationId");
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative.");
            }
        }
    }

    private record BranchTask(
            PendingInvocation invocation,
            DefaultRunCancellation cancellation,
            RunCancellationRegistration parentRegistration,
            CompletableFuture<Object> execution,
            CompletableFuture<BranchOutcome> outcome) {
        BranchTask {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(cancellation, "cancellation");
            Objects.requireNonNull(parentRegistration, "parentRegistration");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private record BranchOutcome(
            PendingInvocation invocation,
            Object output,
            WorkflowContext context,
            Throwable failure,
            boolean cancelled) {
        BranchOutcome {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(context, "context");
            if (failure == null) {
                Objects.requireNonNull(output, "output");
            }
        }
    }
}
