// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.Map;

/**
 * Contains one SDK-originated session hook invocation.
 *
 * <p>Fields that do not apply to {@link #type()} are {@code null} or empty.
 *
 * @param type hook point
 * @param sessionId session identity
 * @param timestamp hook timestamp
 * @param workingDirectory optional working directory
 * @param toolName optional tool name
 * @param mcpServerName optional MCP server name
 * @param toolCallId optional tool-call identity
 * @param arguments optional strict tool arguments
 * @param result optional strict tool result
 * @param metadata immutable MCP metadata
 * @param prompt optional user prompt
 * @param source optional session-start source
 * @param reason optional session-end or stop reason
 * @param finalMessage optional final message
 * @param error optional sanitized session error
 * @param transcriptPath optional agent transcript path
 * @param stopHookActive optional nested stop-hook marker
 */
public record GitHubCopilotHookRequest(
        GitHubCopilotHookType type,
        String sessionId,
        Instant timestamp,
        String workingDirectory,
        String toolName,
        String mcpServerName,
        String toolCallId,
        StateValue arguments,
        StateValue result,
        Map<String, StateValue> metadata,
        String prompt,
        String source,
        String reason,
        String finalMessage,
        String error,
        String transcriptPath,
        Boolean stopHookActive) {
    /** Creates a validated hook request. */
    public GitHubCopilotHookRequest {
        if (type == null || sessionId == null || sessionId.isBlank() || timestamp == null) {
            throw new IllegalArgumentException("type, sessionId, and timestamp are required.");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
