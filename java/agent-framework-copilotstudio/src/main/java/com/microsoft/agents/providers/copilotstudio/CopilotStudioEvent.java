// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Represents one de-duplicated activity and its advanced cursor.
 *
 * @param type event classification
 * @param activity activity
 * @param cursor cursor after accepting the activity
 */
public record CopilotStudioEvent(
        CopilotStudioEventType type, CopilotStudioActivity activity, CopilotStudioCursor cursor) {
    /** Creates a validated event. */
    public CopilotStudioEvent {
        if (type == null || activity == null || cursor == null) {
            throw new NullPointerException("type, activity, and cursor are required.");
        }
    }
}
