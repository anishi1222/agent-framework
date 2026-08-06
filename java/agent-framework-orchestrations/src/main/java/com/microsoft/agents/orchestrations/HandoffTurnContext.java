// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.Message;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies immutable state to a typed handoff router or termination predicate.
 *
 * @param currentParticipant current participant
 * @param targets immutable registered target map
 * @param transcript canonical transcript
 * @param response latest participant response
 * @param path accepted participant path
 * @param turns completed turns
 * @param handoffs accepted handoffs
 */
public record HandoffTurnContext(
        OrchestrationParticipant currentParticipant,
        Map<String, HandoffTarget> targets,
        List<Message> transcript,
        AgentResponse<?> response,
        List<String> path,
        int turns,
        int handoffs) {
    /** Creates a validated immutable turn context. */
    public HandoffTurnContext {
        currentParticipant = Objects.requireNonNull(currentParticipant, "currentParticipant");
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(targets, "targets")));
        transcript = OrchestrationValidation.copyMessages(transcript);
        response = Objects.requireNonNull(response, "response");
        path = List.copyOf(Objects.requireNonNull(path, "path"));
        if (targets.values().stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("targets contains null");
        }
        if (path.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("path contains null");
        }
        if (turns <= 0) {
            throw new IllegalArgumentException("turns must be greater than zero.");
        }
        if (handoffs < 0) {
            throw new IllegalArgumentException("handoffs must not be negative.");
        }
    }
}
