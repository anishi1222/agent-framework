// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandleSource;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.internal.SingleSubscriberPublisher;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Represents an immutable, reusable workflow graph and its execution lifecycle.
 *
 * <p>Runs are isolated and may execute concurrently. The default branch executor uses Java 25
 * virtual threads and is closed with the workflow. A caller-provided executor is never closed.
 * Synchronous callers must not block a saturated caller-owned executor that is also required by
 * their workflow nodes.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
public final class Workflow<I, O> implements AutoCloseable {
    private final String id;

    private final int schemaVersion;

    private final Class<I> inputType;

    private final Class<O> outputType;

    private final Map<NodeId, WorkflowNode<?, ?>> nodes;

    private final List<Edge> edges;

    private final List<EdgeGroup> edgeGroups;

    private final NodeId entryNodeId;

    private final NodeId outputNodeId;

    private final boolean cyclesAllowed;

    private final String graphFingerprint;

    private final ExecutorService nodeExecutor;

    private final boolean ownsNodeExecutor;

    private final ExecutorService controlExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Set<RunHandle<WorkflowRunResult<O>>> activeRuns = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean closed = new AtomicBoolean();

    Workflow(
            String id,
            int schemaVersion,
            Class<I> inputType,
            Class<O> outputType,
            Map<NodeId, WorkflowNode<?, ?>> nodes,
            List<Edge> edges,
            List<EdgeGroup> edgeGroups,
            NodeId entryNodeId,
            NodeId outputNodeId,
            boolean cyclesAllowed,
            ExecutorService callerExecutor) {
        this.id = WorkflowValidation.requireNonBlank(id, "workflowId");
        this.schemaVersion = schemaVersion;
        this.inputType = Objects.requireNonNull(inputType, "inputType");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(nodes)));
        this.edges = List.copyOf(edges);
        this.edgeGroups = List.copyOf(edgeGroups);
        this.entryNodeId = Objects.requireNonNull(entryNodeId, "entryNodeId");
        this.outputNodeId = Objects.requireNonNull(outputNodeId, "outputNodeId");
        this.cyclesAllowed = cyclesAllowed;
        graphFingerprint = fingerprint();
        ownsNodeExecutor = callerExecutor == null;
        nodeExecutor = ownsNodeExecutor ? Executors.newVirtualThreadPerTaskExecutor() : callerExecutor;
    }

    /**
     * Returns the stable workflow identity.
     *
     * @return workflow identity
     */
    public String id() {
        return id;
    }

    /**
     * Returns the application schema version.
     *
     * @return positive schema version
     */
    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * Returns the workflow input type.
     *
     * @return input type
     */
    public Class<I> inputType() {
        return inputType;
    }

    /**
     * Returns the workflow output type.
     *
     * @return output type
     */
    public Class<O> outputType() {
        return outputType;
    }

    /**
     * Returns immutable nodes in lexical identifier order.
     *
     * @return immutable node map
     */
    public Map<NodeId, WorkflowNode<?, ?>> nodes() {
        return nodes;
    }

    /**
     * Returns immutable direct and conditional edges in stable order.
     *
     * @return immutable edges
     */
    public List<Edge> edges() {
        return edges;
    }

    /**
     * Returns immutable fan-in and fan-out groups in stable order.
     *
     * @return immutable edge groups
     */
    public List<EdgeGroup> edgeGroups() {
        return edgeGroups;
    }

    /**
     * Returns the deterministic graph fingerprint used by checkpoint validation.
     *
     * <p>The fingerprint covers workflow/schema identity, node identifiers and payload types, edge
     * kinds, ordered fan-in/fan-out membership, entry/output nodes, and cycle policy using a
     * type-tagged length-prefixed encoding. Executor behavior and conditional predicates are
     * intentionally excluded so behavior may be reattached when topology and state contracts are
     * unchanged.
     *
     * @return lowercase SHA-256 fingerprint
     */
    public String graphFingerprint() {
        return graphFingerprint;
    }

    /**
     * Reports whether the workflow owns and closes its branch executor.
     *
     * @return {@code true} when the workflow created its branch executor
     */
    public boolean ownsExecutorService() {
        return ownsNodeExecutor;
    }

    /**
     * Runs a workflow asynchronously with default options.
     *
     * @param input workflow input
     * @return terminal result stage
     */
    public CompletionStage<WorkflowRunResult<O>> runAsync(I input) {
        return runAsync(input, WorkflowRunOptions.defaults());
    }

    /**
     * Runs a workflow asynchronously.
     *
     * @param input workflow input
     * @param options run options
     * @return terminal result stage
     */
    public CompletionStage<WorkflowRunResult<O>> runAsync(I input, WorkflowRunOptions options) {
        return startRun(input, options).resultAsync();
    }

    /**
     * Runs a workflow asynchronously with caller-owned cancellation.
     *
     * @param input workflow input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return terminal result stage
     */
    public CompletionStage<WorkflowRunResult<O>> runAsync(
            I input, WorkflowRunOptions options, RunCancellation cancellation) {
        return startRun(input, options, cancellation).resultAsync();
    }

    /**
     * Runs a workflow synchronously with default options.
     *
     * @param input workflow input
     * @return terminal result
     */
    public WorkflowRunResult<O> run(I input) {
        return run(input, WorkflowRunOptions.defaults());
    }

    /**
     * Runs a workflow synchronously.
     *
     * @param input workflow input
     * @param options run options
     * @return terminal result
     */
    public WorkflowRunResult<O> run(I input, WorkflowRunOptions options) {
        return RunHandles.await(startRun(input, options), "Workflow run");
    }

    /**
     * Starts an explicitly cancellable workflow run with default options.
     *
     * @param input workflow input
     * @return run handle
     */
    public RunHandle<WorkflowRunResult<O>> startRun(I input) {
        return startRun(input, WorkflowRunOptions.defaults());
    }

    /**
     * Starts an explicitly cancellable workflow run.
     *
     * @param input workflow input
     * @param options run options
     * @return run handle
     */
    public RunHandle<WorkflowRunResult<O>> startRun(I input, WorkflowRunOptions options) {
        return startRun(input, options, new DefaultRunCancellation());
    }

    /**
     * Starts an explicitly cancellable workflow run linked to caller cancellation.
     *
     * @param input workflow input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    public RunHandle<WorkflowRunResult<O>> startRun(I input, WorkflowRunOptions options, RunCancellation cancellation) {
        requireOpen();
        I checkedInput = inputType.cast(Objects.requireNonNull(input, "input"));
        WorkflowRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        RunHandleSource<WorkflowRunResult<O>> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        launch(source, checkedOptions, event -> {}, runner(source, checkedOptions, checkedInput), terminal -> {});
        return source.handle();
    }

    /**
     * Returns a cold, bounded, single-subscriber event publisher.
     *
     * @param input workflow input
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> runStreaming(I input) {
        return runStreaming(input, WorkflowRunOptions.defaults());
    }

    /**
     * Returns a cold, bounded, single-subscriber event publisher.
     *
     * @param input workflow input
     * @param options run options
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> runStreaming(I input, WorkflowRunOptions options) {
        return runStreaming(input, options, new DefaultRunCancellation());
    }

    /**
     * Returns a cold workflow event publisher linked to caller cancellation.
     *
     * @param input workflow input
     * @param options run options
     * @param cancellation caller-owned cancellation
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> runStreaming(
            I input, WorkflowRunOptions options, RunCancellation cancellation) {
        requireOpen();
        I checkedInput = inputType.cast(Objects.requireNonNull(input, "input"));
        WorkflowRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(cancellation, "cancellation");
        return streamingPublisher(
                checkedOptions, cancellation, (source, events) -> runner(source, checkedOptions, checkedInput, events));
    }

    /**
     * Loads, validates, and resumes a checkpoint asynchronously.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @return resumed terminal result
     */
    public CompletionStage<WorkflowRunResult<O>> resumeAsync(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return startResume(storage, key, options).resultAsync();
    }

    /**
     * Loads, validates, and resumes a checkpoint synchronously.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @return resumed terminal result
     */
    public WorkflowRunResult<O> resume(CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return RunHandles.await(startResume(storage, key, options), "Workflow resume");
    }

    /**
     * Starts an explicitly cancellable checkpoint resume.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @return run handle
     */
    public RunHandle<WorkflowRunResult<O>> startResume(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return startResume(storage, key, options, new DefaultRunCancellation());
    }

    /**
     * Starts a checkpoint resume linked to caller cancellation.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @param cancellation caller-owned cancellation
     * @return run handle
     */
    public RunHandle<WorkflowRunResult<O>> startResume(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options, RunCancellation cancellation) {
        requireOpen();
        RunHandleSource<WorkflowRunResult<O>> source =
                new RunHandleSource<>(Objects.requireNonNull(cancellation, "cancellation"));
        WorkflowRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        WorkflowRunner<I, O> runner = resumeRunner(source, checkedOptions, storage, key, event -> {});
        launch(source, checkedOptions, event -> {}, runner, terminal -> {});
        return source.handle();
    }

    /**
     * Returns a cold event publisher for one checkpoint resume.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> resumeStreaming(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return resumeStreaming(storage, key, options, new DefaultRunCancellation());
    }

    /**
     * Returns a cold event publisher for one checkpoint resume linked to caller cancellation.
     *
     * @param storage checkpoint storage
     * @param key checkpoint key
     * @param options run options used after restore
     * @param cancellation caller-owned cancellation
     * @return workflow event publisher
     */
    public Flow.Publisher<WorkflowEvent> resumeStreaming(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options, RunCancellation cancellation) {
        requireOpen();
        WorkflowRunOptions checkedOptions = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cancellation, "cancellation");
        return streamingPublisher(
                checkedOptions,
                cancellation,
                (source, events) -> resumeRunner(source, checkedOptions, storage, key, events));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        activeRuns.forEach(RunHandle::cancel);
        activeRuns.clear();
        controlExecutor.close();
        if (ownsNodeExecutor) {
            nodeExecutor.close();
        }
    }

    private WorkflowRunner<I, O> runner(
            RunHandleSource<WorkflowRunResult<O>> source, WorkflowRunOptions options, I input) {
        return runner(source, options, input, event -> {});
    }

    private WorkflowRunner<I, O> runner(
            RunHandleSource<WorkflowRunResult<O>> source,
            WorkflowRunOptions options,
            I input,
            Consumer<WorkflowEvent> events) {
        return WorkflowRunner.fresh(this, source.cancellation(), options, input, nodeExecutor, events);
    }

    private WorkflowRunner<I, O> resumeRunner(
            RunHandleSource<WorkflowRunResult<O>> source,
            WorkflowRunOptions options,
            CheckpointStorage storage,
            CheckpointKey key,
            Consumer<WorkflowEvent> events) {
        return WorkflowRunner.resume(
                this,
                source.cancellation(),
                options,
                Objects.requireNonNull(storage, "storage"),
                Objects.requireNonNull(key, "key"),
                nodeExecutor,
                events);
    }

    private Flow.Publisher<WorkflowEvent> streamingPublisher(
            WorkflowRunOptions options, RunCancellation cancellation, StreamingRunnerFactory<I, O> runnerFactory) {
        AtomicReference<RunHandle<WorkflowRunResult<O>>> handle = new AtomicReference<>();
        AtomicReference<SingleSubscriberPublisher<WorkflowEvent>> publisherReference = new AtomicReference<>();
        SingleSubscriberPublisher<WorkflowEvent> publisher = new SingleSubscriberPublisher<>(
                () -> {
                    SingleSubscriberPublisher<WorkflowEvent> current = publisherReference.get();
                    RunHandleSource<WorkflowRunResult<O>> source = new RunHandleSource<>(cancellation);
                    WorkflowRunner<I, O> runner = runnerFactory.create(source, current::emit);
                    handle.set(source.handle());
                    launch(source, options, current::emit, runner, terminal -> {
                        if (terminal == null) {
                            current.complete();
                        } else {
                            current.fail(terminal);
                        }
                    });
                },
                () -> {
                    RunHandle<WorkflowRunResult<O>> current = handle.get();
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

    private void launch(
            RunHandleSource<WorkflowRunResult<O>> source,
            WorkflowRunOptions options,
            Consumer<WorkflowEvent> events,
            WorkflowRunner<I, O> runner,
            Consumer<Throwable> terminal) {
        RunHandle<WorkflowRunResult<O>> handle = source.handle();
        activeRuns.add(handle);
        CompletableFuture<WorkflowRunResult<O>> control = CompletableFuture.supplyAsync(runner::run, controlExecutor);
        source.cancellation().cancelledAsync().whenComplete((ignored, failure) -> {
            runner.cancel();
        });
        control.whenComplete((result, failure) -> {
            activeRuns.remove(handle);
            if (failure == null) {
                source.tryComplete(result);
                terminal.accept(null);
                return;
            }
            Throwable cause = source.cancellation().isCancellationRequested()
                    ? new RunCancelledException()
                    : RunHandles.unwrap(failure);
            source.tryFail(cause);
            terminal.accept(cause);
        });
    }

    WorkflowNode<?, ?> node(NodeId nodeId) {
        return nodes.get(nodeId);
    }

    NodeId entryNodeId() {
        return entryNodeId;
    }

    NodeId outputNodeId() {
        return outputNodeId;
    }

    ExecutorService nodeExecutor() {
        return nodeExecutor;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new WorkflowException("Workflow '" + id + "' is closed.");
        }
    }

    private String fingerprint() {
        return WorkflowGraphEncoding.fingerprint(
                id,
                schemaVersion,
                inputType,
                outputType,
                nodes,
                edges,
                edgeGroups,
                entryNodeId,
                outputNodeId,
                cyclesAllowed);
    }

    String newRunId(WorkflowRunOptions options) {
        return options.runId() == null ? UUID.randomUUID().toString() : options.runId();
    }

    @FunctionalInterface
    private interface StreamingRunnerFactory<I, O> {
        WorkflowRunner<I, O> create(RunHandleSource<WorkflowRunResult<O>> source, Consumer<WorkflowEvent> events);
    }
}
