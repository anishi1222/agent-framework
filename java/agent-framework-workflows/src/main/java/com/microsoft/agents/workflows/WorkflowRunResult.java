// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import java.util.Objects;

/**
 * Represents one finite workflow result.
 *
 * @param <O> workflow output type
 * @param runId logical run identifier
 * @param output terminal workflow output
 * @param state final committed state
 * @param supersteps number of completed supersteps
 * @param checkpointRevision optional last stored checkpoint revision
 */
public record WorkflowRunResult<O>(
        String runId, O output, WorkflowState state, int supersteps, Long checkpointRevision) {
    /** Creates a validated immutable workflow result. */
    public WorkflowRunResult {
        runId = WorkflowValidation.requireNonBlank(runId, "runId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(state, "state");
        if (supersteps <= 0) {
            throw new IllegalArgumentException("supersteps must be greater than zero.");
        }
        if (checkpointRevision != null && checkpointRevision <= 0) {
            throw new IllegalArgumentException("checkpointRevision must be positive when present.");
        }
    }
}
