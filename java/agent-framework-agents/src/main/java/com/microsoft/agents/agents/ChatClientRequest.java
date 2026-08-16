// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.tools.ToolMetadata;
import com.microsoft.agents.tools.ToolMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes one immutable provider-neutral chat completion request.
 *
 * @param messages immutable ordered conversation messages
 * @param options immutable chat options
 * @param tools immutable provider-neutral tool declarations
 * @param toolMode provider-neutral tool-selection mode
 * @param runContext optional explicit agent-run context for an agent-originated request
 */
public record ChatClientRequest(
        List<Message> messages,
        ChatOptions options,
        List<ToolMetadata> tools,
        ToolMode toolMode,
        AgentRunContext runContext) {
    /** Creates and defensively copies a chat client request. */
    public ChatClientRequest {
        messages = AgentValidation.copyMessages(messages);
        options = AgentValidation.requireNonNull(options, "options");
        AgentValidation.requireNonNull(tools, "tools");
        ArrayList<ToolMetadata> copiedTools = new ArrayList<>(tools.size());
        for (ToolMetadata tool : tools) {
            copiedTools.add(AgentValidation.requireNonNull(tool, "tool"));
        }
        tools = List.copyOf(copiedTools);
        toolMode = AgentValidation.requireNonNull(toolMode, "toolMode");
    }

    /**
     * Creates a direct request without tools or an agent run context.
     *
     * @param messages ordered input messages
     * @param options chat options
     */
    public ChatClientRequest(List<Message> messages, ChatOptions options) {
        this(messages, options, List.of(), ToolMode.NONE, null);
    }
}
