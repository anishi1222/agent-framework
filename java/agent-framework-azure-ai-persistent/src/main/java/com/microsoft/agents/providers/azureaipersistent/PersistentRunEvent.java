// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.azureaipersistent;

/**
 * Represents one persistent-run stream update.
 *
 * @param kind provider event kind
 * @param runId optional run identifier
 * @param messageId optional message identifier
 * @param textDelta optional text delta
 * @param run optional run snapshot
 */
public record PersistentRunEvent(String kind, String runId, String messageId, String textDelta, PersistentRun run) {
    /** Creates and validates an event. */
    public PersistentRunEvent {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank.");
        }
    }
}
