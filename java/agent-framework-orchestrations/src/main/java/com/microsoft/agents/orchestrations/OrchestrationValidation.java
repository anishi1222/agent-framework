// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.orchestrations;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class OrchestrationValidation {
    private OrchestrationValidation() {}

    static String requireId(String value, String name) {
        return requireText(value, name);
    }

    static String optionalId(String value, String name) {
        return optionalText(value, name);
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return value;
    }

    static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new ValidationException(name + " must not be blank when present.");
        }
        return value;
    }

    static List<Message> copyMessages(List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        ArrayList<Message> copy = new ArrayList<>(messages.size());
        for (Message message : messages) {
            copy.add(Objects.requireNonNull(message, "messages contains null"));
        }
        return List.copyOf(copy);
    }

    static List<OrchestrationParticipant> copyParticipants(List<OrchestrationParticipant> participants) {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new ValidationException("participants must not be empty.");
        }
        ArrayList<OrchestrationParticipant> copy = new ArrayList<>(participants.size());
        Set<String> identifiers = new HashSet<>();
        Set<com.microsoft.agents.agents.Agent<?>> agents =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (OrchestrationParticipant participant : participants) {
            OrchestrationParticipant checked = Objects.requireNonNull(participant, "participants contains null");
            if (!identifiers.add(checked.id())) {
                throw new ValidationException("Duplicate participant id '" + checked.id() + "'.");
            }
            if (!agents.add(checked.agent())) {
                throw new ValidationException("The same agent instance cannot be registered more than once.");
            }
            copy.add(checked);
        }
        return List.copyOf(copy);
    }

    static List<ParticipantResult> copyParticipantResults(List<ParticipantResult> results) {
        Objects.requireNonNull(results, "participantResults");
        ArrayList<ParticipantResult> copy = new ArrayList<>(results.size());
        for (ParticipantResult result : results) {
            copy.add(Objects.requireNonNull(result, "participantResults contains null"));
        }
        return List.copyOf(copy);
    }
}
