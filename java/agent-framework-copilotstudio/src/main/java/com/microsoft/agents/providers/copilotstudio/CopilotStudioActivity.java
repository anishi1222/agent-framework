// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents one bounded Microsoft Activity protocol value.
 *
 * @param id optional stable activity identity
 * @param type activity type
 * @param text optional text
 * @param timestamp optional creation time
 * @param from optional sender
 * @param recipient optional recipient
 * @param conversationId optional conversation identity
 * @param replyToId optional replied-to activity identity
 * @param name optional event name
 * @param attachments immutable attachments
 * @param citations immutable citations
 * @param value JSON-shaped event or action value
 * @param properties bounded additive properties
 * @param raw complete strict JSON activity
 */
public record CopilotStudioActivity(
        String id,
        String type,
        String text,
        Instant timestamp,
        CopilotStudioChannelAccount from,
        CopilotStudioChannelAccount recipient,
        String conversationId,
        String replyToId,
        String name,
        List<CopilotStudioAttachment> attachments,
        List<CopilotStudioCitation> citations,
        StateValue value,
        Map<String, StateValue> properties,
        StateValue.ObjectValue raw) {
    /** Creates and defensively copies an activity. */
    public CopilotStudioActivity {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank.");
        }
        attachments = List.copyOf(attachments);
        citations = List.copyOf(citations);
        value = value == null ? StateValue.nullValue() : value;
        properties = Map.copyOf(properties);
        if (raw == null) {
            throw new NullPointerException("raw");
        }
    }

    /**
     * Creates a text message with a caller-selected stable identity.
     *
     * @param id activity identity
     * @param conversationId conversation identity
     * @param text text
     * @return user message activity
     */
    public static CopilotStudioActivity message(String id, String conversationId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank.");
        }
        java.util.LinkedHashMap<String, StateValue> raw = new java.util.LinkedHashMap<>();
        raw.put("type", StateValue.string("message"));
        raw.put("text", StateValue.string(text));
        if (id != null && !id.isBlank()) {
            raw.put("id", StateValue.string(id));
        }
        if (conversationId != null && !conversationId.isBlank()) {
            raw.put("conversation", StateValue.object(Map.of("id", StateValue.string(conversationId))));
        }
        return new CopilotStudioActivity(
                id,
                "message",
                text,
                null,
                null,
                null,
                conversationId,
                null,
                null,
                List.of(),
                List.of(),
                StateValue.nullValue(),
                Map.of(),
                StateValue.object(raw));
    }
}
