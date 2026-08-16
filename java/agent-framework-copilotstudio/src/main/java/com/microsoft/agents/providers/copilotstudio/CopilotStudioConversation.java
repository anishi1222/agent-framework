// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

import java.util.List;

/**
 * Contains a conversation identity, cursor, and initial activities.
 *
 * @param conversationId conversation identity
 * @param cursor latest cursor
 * @param activities immutable activities
 */
public record CopilotStudioConversation(
        String conversationId, CopilotStudioCursor cursor, List<CopilotStudioActivity> activities) {
    /** Creates and defensively copies a conversation. */
    public CopilotStudioConversation {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank.");
        }
        if (cursor == null) {
            throw new NullPointerException("cursor");
        }
        activities = List.copyOf(activities);
    }
}
