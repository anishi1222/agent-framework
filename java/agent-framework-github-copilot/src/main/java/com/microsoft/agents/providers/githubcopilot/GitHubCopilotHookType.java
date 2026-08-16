// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

/**
 * Identifies stable official SDK session hook points.
 */
public enum GitHubCopilotHookType {
    /** Runs before a built-in or custom tool executes. */
    PRE_TOOL_USE,
    /** Runs before an MCP tool call is dispatched. */
    PRE_MCP_TOOL_CALL,
    /** Runs after a tool completes successfully. */
    POST_TOOL_USE,
    /** Runs when a user prompt is submitted. */
    USER_PROMPT_SUBMITTED,
    /** Runs when a session starts or resumes. */
    SESSION_START,
    /** Runs when a session ends. */
    SESSION_END,
    /** Runs when the top-level agent reaches a natural stop. */
    AGENT_STOP
}
