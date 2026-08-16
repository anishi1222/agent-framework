// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one provider-neutral model turn used by the tool loop.
 *
 * @param logicalRunId logical run identifier
 * @param messages immutable ordered model history
 * @param tools immutable tool declarations
 * @param toolMode provider-neutral tool mode
 * @param metadata immutable run metadata
 */
public record ToolTurnRequest(
        String logicalRunId,
        List<Message> messages,
        List<ToolMetadata> tools,
        ToolMode toolMode,
        Map<String, StateValue> metadata) {
    /** Creates a validated immutable turn request. */
    public ToolTurnRequest {
        logicalRunId = ToolValidation.requireNonBlank(logicalRunId, "logicalRunId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        Objects.requireNonNull(toolMode, "toolMode");
        metadata = ToolValidation.copyMetadata(metadata);
    }
}
