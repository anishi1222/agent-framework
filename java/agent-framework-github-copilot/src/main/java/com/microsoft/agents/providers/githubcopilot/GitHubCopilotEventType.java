// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Classifies framework-owned Copilot session events.
 */
public enum GitHubCopilotEventType {
    /** User input was recorded. */
    USER_MESSAGE,
    /** A complete assistant message was emitted. */
    ASSISTANT_MESSAGE,
    /** An incremental assistant text delta was emitted. */
    ASSISTANT_MESSAGE_DELTA,
    /** A tool call started. */
    TOOL_EXECUTION_START,
    /** A tool call completed. */
    TOOL_EXECUTION_COMPLETE,
    /** Model usage was reported. */
    USAGE,
    /** The session became idle. */
    IDLE,
    /** The session reported an error. */
    ERROR,
    /** A documented but unmapped upstream event was retained as raw JSON. */
    OTHER
}
