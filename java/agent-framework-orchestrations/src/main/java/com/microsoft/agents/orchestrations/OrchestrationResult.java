// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import java.util.List;
import java.util.Objects;

/**
 * Represents one immutable orchestration domain result.
 *
 * @param <O> terminal output type
 * @param runId logical run identifier
 * @param outcome explicit domain outcome
 * @param terminationReason terminal reason
 * @param output optional terminal output
 * @param participantResults immutable results in declaration or turn order
 * @param transcript immutable canonical transcript
 * @param events immutable deterministic event history
 * @param errors immutable sanitized errors
 * @param continuation optional explicit continuation
 * @param turns number of completed participant turns
 */
public record OrchestrationResult<O>(
        String runId,
        OrchestrationOutcome outcome,
        OrchestrationTerminationReason terminationReason,
        O output,
        List<ParticipantResult> participantResults,
        List<Message> transcript,
        List<OrchestrationEvent> events,
        List<OrchestrationError> errors,
        OrchestrationContinuation continuation,
        int turns) {
    /** Creates and defensively copies an orchestration result. */
    public OrchestrationResult {
        runId = OrchestrationValidation.requireId(runId, "runId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason");
        participantResults = OrchestrationValidation.copyParticipantResults(participantResults);
        transcript = OrchestrationValidation.copyMessages(transcript);
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (events.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("events contains null");
        }
        if (errors.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("errors contains null");
        }
        if (turns < 0) {
            throw new IllegalArgumentException("turns must not be negative.");
        }
        if ((outcome == OrchestrationOutcome.INPUT_REQUIRED) != (continuation != null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "INPUT_REQUIRED results must contain exactly one continuation.");
        }
        if (outcome == OrchestrationOutcome.FAILED && errors.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException("FAILED results must contain at least one error.");
        }
    }

    OrchestrationResult<O> withEvents(List<OrchestrationEvent> replacement) {
        return new OrchestrationResult<>(
                runId,
                outcome,
                terminationReason,
                output,
                participantResults,
                transcript,
                replacement,
                errors,
                continuation,
                turns);
    }
}
