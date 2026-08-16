// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes one completed or input-required functional workflow invocation.
 *
 * @param <O> workflow output type
 */
public final class FunctionalWorkflowRunResult<O> {
    private final String runId;

    private final FunctionalWorkflowRunStatus status;

    private final O output;

    private final List<WorkflowEvent> events;

    private final List<FunctionalInputRequest> pendingRequests;

    private final CheckpointKey checkpointKey;

    private final String checkpointId;

    private final long checkpointRevision;

    FunctionalWorkflowRunResult(
            String runId,
            FunctionalWorkflowRunStatus status,
            O output,
            List<WorkflowEvent> events,
            List<FunctionalInputRequest> pendingRequests,
            CheckpointKey checkpointKey,
            String checkpointId,
            long checkpointRevision) {
        this.runId = WorkflowValidation.requireNonBlank(runId, "runId");
        this.status = Objects.requireNonNull(status, "status");
        this.output = output;
        this.events = List.copyOf(events);
        this.pendingRequests = pendingRequests.stream()
                .sorted(Comparator.comparing(FunctionalInputRequest::requestId))
                .toList();
        this.checkpointKey = checkpointKey;
        this.checkpointId = checkpointId;
        if (checkpointRevision < 0) {
            throw new WorkflowValidationException("checkpointRevision must not be negative.");
        }
        this.checkpointRevision = checkpointRevision;
        if (status == FunctionalWorkflowRunStatus.COMPLETED && !this.pendingRequests.isEmpty()) {
            throw new WorkflowValidationException(
                    "Completed functional workflow results cannot have pending requests.");
        }
        if (status == FunctionalWorkflowRunStatus.INPUT_REQUIRED && this.pendingRequests.isEmpty()) {
            throw new WorkflowValidationException("Input-required results must contain at least one pending request.");
        }
        if ((checkpointKey == null) != (checkpointId == null)) {
            throw new WorkflowValidationException("checkpointKey and checkpointId must both be present or absent.");
        }
        if (checkpointKey == null && checkpointRevision != 0) {
            throw new WorkflowValidationException("checkpointRevision must be zero when no checkpoint is present.");
        }
        if (checkpointKey != null && checkpointRevision <= 0) {
            throw new WorkflowValidationException("checkpointRevision must be positive when a checkpoint is present.");
        }
    }

    /**
     * Returns the logical run identifier.
     *
     * @return run identifier
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the invocation boundary.
     *
     * @return run status
     */
    public FunctionalWorkflowRunStatus status() {
        return status;
    }

    /**
     * Returns the workflow output when the body returned a non-null value.
     *
     * @return optional workflow output
     */
    public Optional<O> output() {
        return Optional.ofNullable(output);
    }

    /**
     * Returns all framework and custom events in sequence order.
     *
     * @return immutable event list
     */
    public List<WorkflowEvent> events() {
        return events;
    }

    /**
     * Returns pending input requests sorted by identifier.
     *
     * @return pending requests
     */
    public List<FunctionalInputRequest> pendingRequests() {
        return pendingRequests;
    }

    /**
     * Returns the checkpoint key when persistence was enabled.
     *
     * @return optional checkpoint key
     */
    public Optional<CheckpointKey> checkpointKey() {
        return Optional.ofNullable(checkpointKey);
    }

    /**
     * Returns the latest checkpoint identifier when persistence was enabled.
     *
     * @return optional checkpoint identifier
     */
    public Optional<String> checkpointId() {
        return Optional.ofNullable(checkpointId);
    }

    /**
     * Returns the latest storage revision, or zero when persistence was disabled.
     *
     * @return checkpoint revision
     */
    public long checkpointRevision() {
        return checkpointRevision;
    }
}
