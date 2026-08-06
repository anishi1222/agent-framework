// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.core.AgentResponse;
import java.util.Optional;

/**
 * Represents one participant invocation in declaration or turn order.
 */
public final class ParticipantResult {
    private final String participantId;

    private final ParticipantStatus status;

    private final AgentResponse<?> response;

    private final AgentContinuation agentContinuation;

    private final OrchestrationError error;

    private ParticipantResult(
            String participantId,
            ParticipantStatus status,
            AgentResponse<?> response,
            AgentContinuation agentContinuation,
            OrchestrationError error) {
        this.participantId = OrchestrationValidation.requireId(participantId, "participantId");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.response = response;
        this.agentContinuation = agentContinuation;
        this.error = error;
        boolean validPayload =
                switch (status) {
                    case COMPLETED -> response != null && agentContinuation == null && error == null;
                    case FAILED -> response == null && agentContinuation == null && error != null;
                    case INPUT_REQUIRED -> response == null && error == null;
                    case SKIPPED -> response == null && agentContinuation == null && error == null;
                };
        if (!validPayload) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Participant result payload does not match status " + status + ".");
        }
    }

    /**
     * Creates a completed result.
     *
     * @param participantId participant identifier
     * @param response terminal agent response
     * @return completed participant result
     */
    public static ParticipantResult completed(String participantId, AgentResponse<?> response) {
        return new ParticipantResult(
                participantId,
                ParticipantStatus.COMPLETED,
                java.util.Objects.requireNonNull(response, "response"),
                null,
                null);
    }

    /**
     * Creates a failed result.
     *
     * @param participantId participant identifier
     * @param failure failure
     * @return failed participant result
     */
    public static ParticipantResult failed(String participantId, Throwable failure) {
        return new ParticipantResult(
                participantId,
                ParticipantStatus.FAILED,
                null,
                null,
                OrchestrationError.from(participantId, java.util.Objects.requireNonNull(failure, "failure")));
    }

    /**
     * Creates an input-required result.
     *
     * @param participantId participant identifier
     * @param continuation underlying agent continuation
     * @return input-required participant result
     */
    public static ParticipantResult inputRequired(String participantId, AgentContinuation continuation) {
        return new ParticipantResult(
                participantId,
                ParticipantStatus.INPUT_REQUIRED,
                null,
                java.util.Objects.requireNonNull(continuation, "continuation"),
                null);
    }

    static ParticipantResult abandonedInputRequired(String participantId) {
        return new ParticipantResult(participantId, ParticipantStatus.INPUT_REQUIRED, null, null, null);
    }

    /**
     * Creates a skipped result.
     *
     * @param participantId participant identifier
     * @return skipped participant result
     */
    public static ParticipantResult skipped(String participantId) {
        return new ParticipantResult(participantId, ParticipantStatus.SKIPPED, null, null, null);
    }

    /**
     * Returns the stable participant identifier.
     *
     * @return participant identifier
     */
    public String participantId() {
        return participantId;
    }

    /**
     * Returns the invocation status.
     *
     * @return participant status
     */
    public ParticipantStatus status() {
        return status;
    }

    /**
     * Returns the response for a completed invocation.
     *
     * @return optional response
     */
    public Optional<AgentResponse<?>> response() {
        return Optional.ofNullable(response);
    }

    /**
     * Returns the underlying approval continuation.
     *
     * @return optional continuation
     */
    public Optional<AgentContinuation> agentContinuation() {
        return Optional.ofNullable(agentContinuation);
    }

    /**
     * Returns the sanitized failure.
     *
     * @return optional error
     */
    public Optional<OrchestrationError> error() {
        return Optional.ofNullable(error);
    }
}
