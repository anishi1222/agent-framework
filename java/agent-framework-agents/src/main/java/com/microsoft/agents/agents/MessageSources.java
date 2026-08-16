// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Reads and immutably applies agent-request message source attribution. */
public final class MessageSources {
    private MessageSources() {}

    /**
     * Returns decoded source attribution.
     *
     * @param message message
     * @return empty when absent or malformed
     */
    public static Optional<AgentRequestMessageSourceAttribution> attribution(Message message) {
        Objects.requireNonNull(message, "message");
        StateValue value = message.metadata().get(AgentRequestMessageSourceAttribution.METADATA_KEY);
        return Optional.ofNullable(AgentRequestMessageSourceAttribution.fromStateValue(value));
    }

    /** Returns the source type, defaulting to {@link AgentRequestMessageSourceType#EXTERNAL}. */
    public static AgentRequestMessageSourceType sourceType(Message message) {
        return attribution(message)
                .map(AgentRequestMessageSourceAttribution::sourceType)
                .orElse(AgentRequestMessageSourceType.EXTERNAL);
    }

    /** Returns the optional source component identifier. */
    public static String sourceId(Message message) {
        return attribution(message)
                .map(AgentRequestMessageSourceAttribution::sourceId)
                .orElse(null);
    }

    /**
     * Returns the original message when attribution already matches, otherwise an immutable copy.
     *
     * @param message source message
     * @param sourceType component category
     * @param sourceId optional component identifier
     * @return attributed message
     */
    public static Message withSource(Message message, AgentRequestMessageSourceType sourceType, String sourceId) {
        Objects.requireNonNull(message, "message");
        AgentRequestMessageSourceAttribution attribution =
                new AgentRequestMessageSourceAttribution(sourceType, sourceId);
        if (attribution.equals(attribution(message).orElse(null))) {
            return message;
        }
        LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>(message.metadata());
        metadata.put(AgentRequestMessageSourceAttribution.METADATA_KEY, attribution.toStateValue());
        return new Message(message.role(), message.contents(), message.authorName(), message.messageId(), metadata);
    }

    static List<Message> withSource(List<Message> messages, AgentRequestMessageSourceType sourceType, String sourceId) {
        AgentValidation.requireNonNull(messages, "messages");
        return messages.stream()
                .map(message -> withSource(message, sourceType, sourceId))
                .toList();
    }
}
