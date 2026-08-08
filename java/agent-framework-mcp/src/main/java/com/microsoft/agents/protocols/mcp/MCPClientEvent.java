// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import com.microsoft.agents.core.StateValue;
import java.util.List;

/**
 * Represents a bounded asynchronous notification received from an MCP server.
 */
public sealed interface MCPClientEvent
        permits MCPClientEvent.Progress,
                MCPClientEvent.Log,
                MCPClientEvent.ToolsChanged,
                MCPClientEvent.ResourcesChanged,
                MCPClientEvent.ResourcesUpdated,
                MCPClientEvent.PromptsChanged,
                MCPClientEvent.ElicitationCompleted {
    /**
     * Reports MCP operation progress.
     *
     * @param token JSON-shaped progress token
     * @param progress current progress
     * @param total optional total
     * @param message optional human-readable message
     */
    record Progress(StateValue token, double progress, Double total, String message) implements MCPClientEvent {
        /** Creates an immutable progress event. */
        public Progress {
            java.util.Objects.requireNonNull(token, "token");
            message = message == null ? "" : message;
        }
    }

    /**
     * Reports an MCP logging notification.
     *
     * @param level severity
     * @param logger optional logger name
     * @param message redacted log message
     */
    record Log(MCPLogLevel level, String logger, String message) implements MCPClientEvent {
        /** Creates an immutable log event. */
        public Log {
            java.util.Objects.requireNonNull(level, "level");
            logger = logger == null ? "" : logger;
            message = java.util.Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Reports a replacement tool list.
     *
     * @param tools immutable tools
     */
    record ToolsChanged(List<MCPToolDescriptor> tools) implements MCPClientEvent {
        /** Creates an immutable tool-list event. */
        public ToolsChanged {
            tools = MCPValidation.copyList(tools, "tools");
        }
    }

    /**
     * Reports a replacement resource list.
     *
     * @param resources immutable resources
     */
    record ResourcesChanged(List<MCPResourceDescriptor> resources) implements MCPClientEvent {
        /** Creates an immutable resource-list event. */
        public ResourcesChanged {
            resources = MCPValidation.copyList(resources, "resources");
        }
    }

    /**
     * Reports updated resource contents.
     *
     * @param contents immutable resource contents
     */
    record ResourcesUpdated(List<MCPResourceContents> contents) implements MCPClientEvent {
        /** Creates an immutable resource-update event. */
        public ResourcesUpdated {
            contents = MCPValidation.copyList(contents, "contents");
        }
    }

    /**
     * Reports a replacement prompt list.
     *
     * @param prompts immutable prompts
     */
    record PromptsChanged(List<MCPPromptDescriptor> prompts) implements MCPClientEvent {
        /** Creates an immutable prompt-list event. */
        public PromptsChanged {
            prompts = MCPValidation.copyList(prompts, "prompts");
        }
    }

    /**
     * Reports completion of an out-of-band URL elicitation.
     *
     * @param elicitationId elicitation identifier
     */
    record ElicitationCompleted(String elicitationId) implements MCPClientEvent {
        /** Creates an immutable completion event. */
        public ElicitationCompleted {
            elicitationId = MCPValidation.nonBlank(elicitationId, "elicitationId");
        }
    }
}
