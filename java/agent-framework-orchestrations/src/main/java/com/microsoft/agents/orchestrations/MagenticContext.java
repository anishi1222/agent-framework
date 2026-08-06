// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies immutable run state to a Magentic manager or planner.
 *
 * @param participants participants in deterministic declaration order
 * @param ledger immutable ledger snapshot
 * @param cancellation run cancellation
 * @param agentRunOptions options available to agent-backed managers
 * @param metadata immutable orchestration metadata
 */
public record MagenticContext(
        Map<String, OrchestrationParticipant> participants,
        MagenticLedger ledger,
        RunCancellation cancellation,
        RunOptions agentRunOptions,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable manager context. */
    public MagenticContext {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new com.microsoft.agents.core.ValidationException("participants must not be empty.");
        }
        LinkedHashMap<String, OrchestrationParticipant> copy = new LinkedHashMap<>();
        participants.forEach((id, participant) -> copy.put(
                OrchestrationValidation.requireId(id, "participant id"),
                Objects.requireNonNull(participant, "participant")));
        participants = Collections.unmodifiableMap(copy);
        ledger = Objects.requireNonNull(ledger, "ledger");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
        agentRunOptions = Objects.requireNonNull(agentRunOptions, "agentRunOptions");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
