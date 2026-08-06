// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

/**
 * Represents a validated manager decision to select a speaker or terminate.
 *
 * @param participantId selected participant identifier, or {@code null} when terminating
 * @param terminate whether the chat should terminate
 * @param reason optional manager reason
 */
public record GroupChatDecision(String participantId, boolean terminate, String reason) {
    /** Creates a validated immutable decision. */
    public GroupChatDecision {
        participantId = OrchestrationValidation.optionalId(participantId, "participantId");
        reason = OrchestrationValidation.optionalText(reason, "reason");
        if (terminate == (participantId != null)) {
            throw new com.microsoft.agents.core.ValidationException(
                    "A group-chat decision must either terminate or select one participant.");
        }
    }

    /**
     * Creates a speaker-selection decision.
     *
     * @param participantId selected participant
     * @return selection decision
     */
    public static GroupChatDecision select(String participantId) {
        return new GroupChatDecision(participantId, false, null);
    }

    /**
     * Creates a termination decision.
     *
     * @param reason optional termination reason
     * @return termination decision
     */
    public static GroupChatDecision terminate(String reason) {
        return new GroupChatDecision(null, true, reason);
    }
}
