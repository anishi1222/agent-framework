// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Represents one immutable Magentic progress assessment.
 *
 * @param requestSatisfied whether the original request is fully solved
 * @param progressMade whether the latest turn made forward progress
 * @param stalled whether the manager detected a loop or barrier
 * @param nextParticipantId optional registered next participant
 * @param instruction optional next instruction
 * @param rationale non-blank assessment rationale
 */
public record MagenticProgressAssessment(
        boolean requestSatisfied,
        boolean progressMade,
        boolean stalled,
        String nextParticipantId,
        String instruction,
        String rationale) {
    /** Creates a validated immutable assessment. */
    public MagenticProgressAssessment {
        nextParticipantId = OrchestrationValidation.optionalId(nextParticipantId, "nextParticipantId");
        instruction = OrchestrationValidation.optionalText(instruction, "instruction");
        rationale = OrchestrationValidation.requireText(rationale, "rationale");
        if (requestSatisfied && nextParticipantId != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "A satisfied assessment must not assign another participant.");
        }
    }
}
