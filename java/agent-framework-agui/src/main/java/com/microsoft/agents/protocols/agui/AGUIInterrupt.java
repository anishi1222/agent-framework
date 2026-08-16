// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.Map;

/**
 * Represents one official interrupt carried by an interrupt run outcome.
 *
 * @param id opaque interrupt correlation identifier
 * @param reason core or namespaced reason
 * @param message optional human-facing prompt
 * @param toolCallId optional originating tool-call identifier
 * @param responseSchema optional JSON Schema for the resume payload
 * @param expiresAt optional expiry instant
 * @param metadata arbitrary immutable metadata
 */
public record AGUIInterrupt(
        String id,
        String reason,
        String message,
        String toolCallId,
        StateValue.ObjectValue responseSchema,
        Instant expiresAt,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable interrupt. */
    public AGUIInterrupt {
        id = AGUIValidation.nonBlank(id, "id");
        reason = AGUIValidation.nonBlank(reason, "reason");
        message = AGUIValidation.optionalNonBlank(message, "message");
        toolCallId = AGUIValidation.optionalNonBlank(toolCallId, "toolCallId");
        metadata = AGUIValidation.map(metadata, "metadata");
        if ("tool_call".equals(reason) && toolCallId == null) {
            throw AGUIValidation.invalid("tool_call interrupt requires toolCallId.");
        }
    }
}
