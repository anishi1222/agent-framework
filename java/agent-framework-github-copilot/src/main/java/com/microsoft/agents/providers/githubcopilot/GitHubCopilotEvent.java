// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.UsageDetails;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents one bounded, framework-owned Copilot session event.
 *
 * @param sequence local monotonically increasing sequence
 * @param type event classification
 * @param upstreamType exact upstream type
 * @param sessionId session identity
 * @param eventId optional upstream event identity
 * @param messageId optional message identity
 * @param toolCallId optional tool-call identity
 * @param toolName optional tool name
 * @param arguments optional strict tool arguments
 * @param result optional strict tool result
 * @param success optional tool success flag
 * @param model optional model
 * @param text optional text or sanitized error message
 * @param usage optional usage
 * @param timestamp optional timestamp
 * @param raw bounded strict JSON event
 */
public record GitHubCopilotEvent(
        long sequence,
        GitHubCopilotEventType type,
        String upstreamType,
        String sessionId,
        String eventId,
        String messageId,
        String toolCallId,
        String toolName,
        StateValue.ObjectValue arguments,
        StateValue result,
        Boolean success,
        String model,
        String text,
        UsageDetails usage,
        Instant timestamp,
        StateValue.ObjectValue raw) {
    /** Creates a validated event. */
    public GitHubCopilotEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative.");
        }
        type = Objects.requireNonNull(type, "type");
        upstreamType = required(upstreamType, "upstreamType");
        sessionId = required(sessionId, "sessionId");
        raw = Objects.requireNonNull(raw, "raw");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
