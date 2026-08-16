// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.codeact;

import java.util.List;
import java.util.Objects;

/**
 * Represents one immutable bounded CodeAct result.
 *
 * @param runId deterministic logical run identifier
 * @param status terminal status
 * @param state deterministic terminal state
 * @param steps completed step results in program order
 * @param events deterministic event history
 * @param detail optional terminal detail
 */
public record CodeActResult(
        String runId,
        CodeActStatus status,
        CodeActState state,
        List<CodeActStepResult> steps,
        List<CodeActEvent> events,
        String detail) {
    /** Creates a validated immutable result. */
    public CodeActResult {
        runId = CodeActValidation.requireNonBlank(runId, "runId");
        status = Objects.requireNonNull(status, "status");
        state = Objects.requireNonNull(state, "state");
        steps = CodeActValidation.copyList(steps, "steps");
        events = CodeActValidation.copyList(events, "events");
        detail = CodeActValidation.optionalNonBlank(detail, "detail");
        if (!runId.equals(state.runId()) || status != state.status()) {
            throw new IllegalArgumentException("Result identity and status must match terminal state.");
        }
    }

    /**
     * Reports whether every submitted step completed successfully.
     *
     * @return {@code true} only for {@link CodeActStatus#COMPLETED}
     */
    public boolean completed() {
        return status == CodeActStatus.COMPLETED;
    }

    /**
     * Renders the event history as deterministic canonical text.
     *
     * @return stable line-oriented transcript without timestamps or measured durations
     */
    public String transcript() {
        return CodeActTranscript.render(events);
    }
}
