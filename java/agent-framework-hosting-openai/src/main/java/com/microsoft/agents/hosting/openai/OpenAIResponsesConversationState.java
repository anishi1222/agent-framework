// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.Message;
import java.time.Instant;
import java.util.List;

/**
 * Represents one immutable principal-scoped transcript snapshot.
 *
 * @param messages complete transcript
 * @param activeRequestId active mutable-conversation request, or {@code null}
 * @param updatedAt last successful compare-and-set time
 */
public record OpenAIResponsesConversationState(List<Message> messages, String activeRequestId, Instant updatedAt) {
    /** Creates a validated immutable state. */
    public OpenAIResponsesConversationState {
        messages = List.copyOf(java.util.Objects.requireNonNull(messages, "messages"));
        if (activeRequestId != null && activeRequestId.isBlank()) {
            throw new IllegalArgumentException("activeRequestId must not be blank.");
        }
        java.util.Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Creates an inactive state.
     *
     * @param messages transcript
     * @param now update time
     * @return inactive state
     */
    public static OpenAIResponsesConversationState inactive(List<Message> messages, Instant now) {
        return new OpenAIResponsesConversationState(messages, null, now);
    }
}
