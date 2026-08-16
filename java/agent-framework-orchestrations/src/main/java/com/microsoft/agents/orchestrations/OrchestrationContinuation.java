// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.agents.AgentContinuation;
import com.microsoft.agents.core.Message;
import java.util.List;

/**
 * Describes an explicit suspended orchestration boundary.
 *
 * <p>The descriptor identifies process-local state retained by the creating orchestration instance.
 * It is not a checkpoint and provides no cross-process or post-close resume guarantee. When
 * {@link #agentContinuation()} is present, callers retain the underlying agent approval authority,
 * but the enclosing pattern state remains process-local. Resume validates the orchestration, pattern,
 * run, participant, input kind, and one-time continuation identity.
 *
 * @param continuationId stable one-time continuation identifier
 * @param orchestrationId owning orchestration identifier
 * @param runId logical run identifier
 * @param pattern owning orchestration pattern
 * @param kind required input kind
 * @param participantId optional participant awaiting input
 * @param agentContinuation optional underlying approval continuation
 * @param transcript immutable conversation at suspension
 * @param prompt optional human-facing request
 * @param restartCapable reserved capability flag; orchestration continuations require {@code false}
 */
public record OrchestrationContinuation(
        String continuationId,
        String orchestrationId,
        String runId,
        OrchestrationPattern pattern,
        OrchestrationContinuationKind kind,
        String participantId,
        AgentContinuation agentContinuation,
        List<Message> transcript,
        String prompt,
        boolean restartCapable) {
    /** Creates a validated immutable continuation descriptor. */
    public OrchestrationContinuation {
        continuationId = OrchestrationValidation.requireId(continuationId, "continuationId");
        orchestrationId = OrchestrationValidation.requireId(orchestrationId, "orchestrationId");
        runId = OrchestrationValidation.requireId(runId, "runId");
        java.util.Objects.requireNonNull(pattern, "pattern");
        java.util.Objects.requireNonNull(kind, "kind");
        participantId = OrchestrationValidation.optionalId(participantId, "participantId");
        transcript = OrchestrationValidation.copyMessages(transcript);
        prompt = OrchestrationValidation.optionalText(prompt, "prompt");
        if (kind == OrchestrationContinuationKind.APPROVAL && agentContinuation == null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "agentContinuation is required for APPROVAL continuations.");
        }
        if (restartCapable) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Orchestration continuations are process-local and cannot be restartCapable.");
        }
    }
}
