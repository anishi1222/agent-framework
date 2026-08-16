// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.Tool;
import java.util.List;
import java.util.Map;

/**
 * Carries the immutable context accumulated before one provider executes.
 *
 * @param session active session
 * @param runContext explicit run context and cancellation
 * @param messages ordered messages accumulated so far
 * @param instructions ordered instructions accumulated so far
 * @param metadata immutable accumulated metadata
 * @param tools immutable accumulated tools
 */
public record ContextProviderRequest(
        AgentSession session,
        AgentRunContext runContext,
        List<Message> messages,
        List<String> instructions,
        Map<String, StateValue> metadata,
        List<Tool> tools) {
    /** Creates and defensively copies a provider request. */
    public ContextProviderRequest {
        session = AgentValidation.requireNonNull(session, "session");
        runContext = AgentValidation.requireNonNull(runContext, "runContext");
        messages = AgentValidation.copyMessages(messages);
        instructions = List.copyOf(AgentValidation.requireNonNull(instructions, "instructions"));
        metadata = AgentValidation.copyMetadata(metadata);
        tools = List.copyOf(AgentValidation.requireNonNull(tools, "tools"));
    }
}
