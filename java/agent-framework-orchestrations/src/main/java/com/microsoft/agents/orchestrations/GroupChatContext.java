// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies immutable state to a group-chat manager, selector, or termination predicate.
 *
 * @param participants participants in deterministic declaration order
 * @param transcript shared canonical transcript
 * @param previousSpeakerId optional previous speaker identifier
 * @param turn zero-based next turn
 * @param cancellation run cancellation
 * @param agentRunOptions options available to agent-based selectors
 * @param metadata immutable orchestration metadata
 */
public record GroupChatContext(
        Map<String, OrchestrationParticipant> participants,
        List<Message> transcript,
        String previousSpeakerId,
        int turn,
        RunCancellation cancellation,
        RunOptions agentRunOptions,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable context. */
    public GroupChatContext {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException("participants must not be empty.");
        }
        LinkedHashMap<String, OrchestrationParticipant> copy = new LinkedHashMap<>();
        participants.forEach((id, participant) -> copy.put(
                OrchestrationValidation.requireId(id, "participant id"),
                Objects.requireNonNull(participant, "participant")));
        participants = Collections.unmodifiableMap(copy);
        transcript = OrchestrationValidation.copyMessages(transcript);
        previousSpeakerId = OrchestrationValidation.optionalId(previousSpeakerId, "previousSpeakerId");
        if (turn < 0) {
            throw new IllegalArgumentException("turn must not be negative.");
        }
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
        agentRunOptions = Objects.requireNonNull(agentRunOptions, "agentRunOptions");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
