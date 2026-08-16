// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Classifies official SDK client-level session lifecycle events.
 */
public enum GitHubCopilotSessionLifecycleEventType {
    /** A session was created. */
    CREATED,
    /** A session was deleted. */
    DELETED,
    /** Persisted session metadata changed. */
    UPDATED,
    /** A session became foreground in TUI server mode. */
    FOREGROUND,
    /** A session became background in TUI server mode. */
    BACKGROUND,
    /** A newer SDK supplied a lifecycle type not yet specially classified. */
    OTHER
}
