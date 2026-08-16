// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Represents a monotonic local cursor and the upstream SSE resume identity.
 *
 * @param lastEventId optional SSE {@code Last-Event-ID}
 * @param sequence non-negative local event sequence
 */
public record CopilotStudioCursor(String lastEventId, long sequence) {
    /** Creates a validated cursor. */
    public CopilotStudioCursor {
        lastEventId = lastEventId == null || lastEventId.isBlank() ? null : lastEventId;
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative.");
        }
    }

    /**
     * Returns the resume cursor using Direct Line-compatible terminology.
     *
     * <p>Direct-to-Engine uses SSE {@code Last-Event-ID}, not a Direct Line numeric watermark.
     *
     * @return last SSE event identity
     */
    public String watermark() {
        return lastEventId;
    }

    /**
     * Returns an empty cursor.
     *
     * @return empty cursor
     */
    public static CopilotStudioCursor empty() {
        return new CopilotStudioCursor(null, 0);
    }
}
