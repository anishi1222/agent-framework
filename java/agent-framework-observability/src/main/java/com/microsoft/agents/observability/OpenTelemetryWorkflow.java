// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.observability;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.workflows.CheckpointKey;
import com.microsoft.agents.workflows.CheckpointStorage;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowEvent;
import com.microsoft.agents.workflows.WorkflowRunOptions;
import com.microsoft.agents.workflows.WorkflowRunResult;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides an observable facade over an immutable workflow.
 *
 * <p>The wrapped workflow remains caller-owned unless {@code closeDelegate} is explicitly enabled.
 *
 * @param <I> workflow input type
 * @param <O> workflow output type
 */
@SuppressWarnings("try")
public final class OpenTelemetryWorkflow<I, O> implements AutoCloseable {
    private final Workflow<I, O> delegate;

    private final AgentFrameworkTelemetry telemetry;

    private final TelemetrySanitizer sanitizer;

    private final boolean closeDelegate;

    /**
     * Creates a non-owning workflow facade.
     *
     * @param delegate caller-owned workflow
     * @param telemetry telemetry configuration
     */
    public OpenTelemetryWorkflow(Workflow<I, O> delegate, AgentFrameworkTelemetry telemetry) {
        this(delegate, telemetry, false);
    }

    /**
     * Creates a workflow facade.
     *
     * @param delegate workflow
     * @param telemetry telemetry configuration
     * @param closeDelegate whether closing this facade closes the workflow
     */
    public OpenTelemetryWorkflow(Workflow<I, O> delegate, AgentFrameworkTelemetry telemetry, boolean closeDelegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.sanitizer = new TelemetrySanitizer(telemetry);
        this.closeDelegate = closeDelegate;
    }

    /**
     * Returns the wrapped workflow.
     *
     * @return workflow
     */
    public Workflow<I, O> workflow() {
        return delegate;
    }

    /** Runs asynchronously with default options. */
    public CompletionStage<WorkflowRunResult<O>> runAsync(I input) {
        return runAsync(input, WorkflowRunOptions.defaults());
    }

    /** Runs asynchronously with options. */
    public CompletionStage<WorkflowRunResult<O>> runAsync(I input, WorkflowRunOptions options) {
        return startRun(input, options).resultAsync();
    }

    /** Runs asynchronously with caller-owned cancellation. */
    public CompletionStage<WorkflowRunResult<O>> runAsync(
            I input, WorkflowRunOptions options, RunCancellation cancellation) {
        return startRun(input, options, cancellation).resultAsync();
    }

    /** Runs synchronously with default options. */
    public WorkflowRunResult<O> run(I input) {
        return run(input, WorkflowRunOptions.defaults());
    }

    /** Runs synchronously with options. */
    public WorkflowRunResult<O> run(I input, WorkflowRunOptions options) {
        return RunHandles.await(startRun(input, options), "Observed workflow run");
    }

    /** Starts a run with default options. */
    public RunHandle<WorkflowRunResult<O>> startRun(I input) {
        return startRun(input, WorkflowRunOptions.defaults());
    }

    /** Starts a run with framework-owned cancellation. */
    public RunHandle<WorkflowRunResult<O>> startRun(I input, WorkflowRunOptions options) {
        return startRun(input, options, new DefaultRunCancellation());
    }

    /** Starts a run with caller-owned cancellation. */
    public RunHandle<WorkflowRunResult<O>> startRun(I input, WorkflowRunOptions options, RunCancellation cancellation) {
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (TelemetryContext.suppressed(TelemetryContext.WORKFLOW_ACTIVE)) {
            return delegate.startRun(input, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        WorkflowRunOptions observedOptions = instrumentedOptions(options, correlationId);
        TelemetryOperation operation = start(input, false, correlationId);
        telemetry.contextRegistry().registerWorkflow(correlationId, operation.context(), operation::abandoned);
        RunHandle<WorkflowRunResult<O>> handle;
        try {
            handle = operation.callWithContext(() -> delegate.startRun(input, observedOptions, cancellation));
        } catch (RuntimeException failure) {
            operation.failure(failure);
            throw failure;
        }
        if (handle == null) {
            IllegalStateException failure = new IllegalStateException("Workflow.startRun returned null.");
            operation.failure(failure);
            throw failure;
        }
        CompletionStage<WorkflowRunResult<O>> observed =
                TelemetryStages.observe(handle.resultAsync(), operation, result -> recordResult(operation, result));
        return new ObservedRunHandle<>(observed, handle.cancellation());
    }

    /** Streams one run with default options. */
    public Flow.Publisher<WorkflowEvent> runStreaming(I input) {
        return runStreaming(input, WorkflowRunOptions.defaults());
    }

    /** Streams one run with framework-owned cancellation. */
    public Flow.Publisher<WorkflowEvent> runStreaming(I input, WorkflowRunOptions options) {
        return runStreaming(input, options, new DefaultRunCancellation());
    }

    /** Streams one run with caller-owned cancellation. */
    public Flow.Publisher<WorkflowEvent> runStreaming(
            I input, WorkflowRunOptions options, RunCancellation cancellation) {
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (TelemetryContext.suppressed(TelemetryContext.WORKFLOW_ACTIVE)) {
            return delegate.runStreaming(input, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        WorkflowRunOptions observedOptions = instrumentedOptions(options, correlationId);
        AtomicReference<TelemetryOperation> operationReference = new AtomicReference<>();
        return new TelemetryPublisher<>(
                () -> {
                    TelemetryOperation operation = start(input, false, correlationId);
                    telemetry
                            .contextRegistry()
                            .registerWorkflow(correlationId, operation.context(), operation::abandoned);
                    operationReference.set(operation);
                    return operation;
                },
                () -> delegate.runStreaming(input, observedOptions, cancellation),
                event -> recordEvent(operationReference.get(), event),
                cancellation);
    }

    /** Resumes a checkpoint asynchronously. */
    public CompletionStage<WorkflowRunResult<O>> resumeAsync(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return startResume(storage, key, options).resultAsync();
    }

    /** Resumes a checkpoint synchronously. */
    public WorkflowRunResult<O> resume(CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return RunHandles.await(startResume(storage, key, options), "Observed workflow resume");
    }

    /** Starts a checkpoint resume with framework-owned cancellation. */
    public RunHandle<WorkflowRunResult<O>> startResume(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options) {
        return startResume(storage, key, options, new DefaultRunCancellation());
    }

    /** Starts a checkpoint resume with caller-owned cancellation. */
    public RunHandle<WorkflowRunResult<O>> startResume(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options, RunCancellation cancellation) {
        java.util.Objects.requireNonNull(storage, "storage");
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (TelemetryContext.suppressed(TelemetryContext.WORKFLOW_ACTIVE)) {
            return delegate.startResume(storage, key, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        WorkflowRunOptions observedOptions = instrumentedOptions(options, correlationId);
        TelemetryOperation operation = start(key.value(), true, correlationId);
        telemetry.contextRegistry().registerWorkflow(correlationId, operation.context(), operation::abandoned);
        RunHandle<WorkflowRunResult<O>> handle;
        try {
            handle = operation.callWithContext(() -> delegate.startResume(storage, key, observedOptions, cancellation));
        } catch (RuntimeException failure) {
            operation.failure(failure);
            throw failure;
        }
        if (handle == null) {
            IllegalStateException failure = new IllegalStateException("Workflow.startResume returned null.");
            operation.failure(failure);
            throw failure;
        }
        CompletionStage<WorkflowRunResult<O>> observed =
                TelemetryStages.observe(handle.resultAsync(), operation, result -> recordResult(operation, result));
        return new ObservedRunHandle<>(observed, handle.cancellation());
    }

    /** Streams one checkpoint resume. */
    public Flow.Publisher<WorkflowEvent> resumeStreaming(
            CheckpointStorage storage, CheckpointKey key, WorkflowRunOptions options, RunCancellation cancellation) {
        java.util.Objects.requireNonNull(storage, "storage");
        java.util.Objects.requireNonNull(key, "key");
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(cancellation, "cancellation");
        if (TelemetryContext.suppressed(TelemetryContext.WORKFLOW_ACTIVE)) {
            return delegate.resumeStreaming(storage, key, options, cancellation);
        }
        String correlationId = UUID.randomUUID().toString();
        WorkflowRunOptions observedOptions = instrumentedOptions(options, correlationId);
        AtomicReference<TelemetryOperation> operationReference = new AtomicReference<>();
        return new TelemetryPublisher<>(
                () -> {
                    TelemetryOperation operation = start(key.value(), true, correlationId);
                    telemetry
                            .contextRegistry()
                            .registerWorkflow(correlationId, operation.context(), operation::abandoned);
                    operationReference.set(operation);
                    return operation;
                },
                () -> delegate.resumeStreaming(storage, key, observedOptions, cancellation),
                event -> recordEvent(operationReference.get(), event),
                cancellation);
    }

    @Override
    public void close() {
        if (closeDelegate) {
            delegate.close();
        }
    }

    private TelemetryOperation start(Object input, boolean resumed, String correlationId) {
        TelemetryOperation operation = TelemetryOperation.start(
                telemetry,
                "invoke_workflow " + delegate.id(),
                SpanKind.INTERNAL,
                "invoke_workflow",
                null,
                TelemetryContext.WORKFLOW_ACTIVE,
                Attributes.builder()
                        .put(GenAiAttributes.WORKFLOW_NAME, delegate.id())
                        .put("agent_framework.workflow.resumed", resumed)
                        .build(),
                io.opentelemetry.context.Context.current(),
                () -> telemetry.contextRegistry().removeWorkflow(correlationId));
        operation.stringAttribute("agent_framework.workflow.input", () -> sanitizer.value(input));
        return operation;
    }

    private static WorkflowRunOptions instrumentedOptions(WorkflowRunOptions options, String correlationId) {
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(options.metadata());
        metadata.put(TelemetryContextRegistry.CORRELATION_METADATA_KEY, StateValue.string(correlationId));
        WorkflowRunOptions.Builder builder = WorkflowRunOptions.builder()
                .maxSupersteps(options.maxSupersteps())
                .maxBufferedEvents(options.maxBufferedEvents())
                .initialState(options.initialState())
                .valueEncoder(options.valueEncoder())
                .metadata(Map.copyOf(metadata));
        if (options.runId() != null) {
            builder.runId(options.runId());
        }
        if (options.checkpointStorage() != null) {
            builder.checkpoint(
                    options.checkpointStorage(), options.checkpointKey(), options.expectedCheckpointRevision());
        }
        return builder.build();
    }

    private void recordResult(TelemetryOperation operation, WorkflowRunResult<O> result) {
        if (result == null) {
            throw new IllegalStateException("Workflow completed with null result.");
        }
        operation.stringAttribute(GenAiAttributes.RUN_ID, () -> sanitizer.identifier(result.runId()));
        operation.longAttribute("agent_framework.workflow.supersteps", result.supersteps());
        operation.stringAttribute("agent_framework.workflow.output", () -> sanitizer.value(result.output()));
    }

    private void recordEvent(TelemetryOperation operation, WorkflowEvent event) {
        if (event == null) {
            throw new IllegalStateException("Workflow emitted a null event.");
        }
        operation.stringAttribute(GenAiAttributes.RUN_ID, () -> sanitizer.identifier(event.runId()));
        io.opentelemetry.api.common.AttributesBuilder attributes = io.opentelemetry.api.common.Attributes.builder()
                .put("agent_framework.workflow.event.type", event.type().value())
                .put("agent_framework.workflow.event.sequence", event.sequence())
                .put("agent_framework.workflow.event.superstep", event.superstep());
        if (event.nodeId() != null) {
            String nodeId = operation.instrumentationValue(
                    () -> sanitizer.identifier(event.nodeId().value()));
            if (nodeId != null) {
                attributes.put("agent_framework.workflow.node.id", nodeId);
            }
        }
        operation.event("agent_framework.workflow." + event.type().value(), attributes.build());
    }
}
