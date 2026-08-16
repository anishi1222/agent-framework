// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.util.Objects;

/**
 * Represents deterministic terminal state for one CodeAct run.
 *
 * @param runId deterministic logical run identifier
 * @param programDigest SHA-256 digest of the exact bounded program configuration
 * @param status terminal run status
 * @param nextStepIndex zero-based next unexecuted step index
 * @param completedSteps number of completed shell steps
 * @param capturedOutputBytes total retained UTF-8 output bytes
 * @param outputTruncated whether shell or aggregate output exceeded a configured bound
 */
public record CodeActState(
        String runId,
        String programDigest,
        CodeActStatus status,
        int nextStepIndex,
        int completedSteps,
        int capturedOutputBytes,
        boolean outputTruncated) {
    /** Creates validated immutable terminal state. */
    public CodeActState {
        runId = CodeActValidation.requireNonBlank(runId, "runId");
        programDigest = CodeActValidation.requireNonBlank(programDigest, "programDigest");
        status = Objects.requireNonNull(status, "status");
        if (nextStepIndex < 0) {
            throw new IllegalArgumentException("nextStepIndex must not be negative.");
        }
        if (completedSteps < 0 || completedSteps > nextStepIndex) {
            throw new IllegalArgumentException("completedSteps must be between zero and nextStepIndex.");
        }
        if (capturedOutputBytes < 0) {
            throw new IllegalArgumentException("capturedOutputBytes must not be negative.");
        }
    }
}
