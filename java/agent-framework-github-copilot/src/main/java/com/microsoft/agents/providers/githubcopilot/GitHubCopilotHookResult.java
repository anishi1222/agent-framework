// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.microsoft.agents.core.StateValue;
import java.util.List;
import java.util.Map;

/**
 * Represents a framework-owned result for one stable official SDK session hook.
 */
public sealed interface GitHubCopilotHookResult
        permits GitHubCopilotHookResult.NoChange,
                GitHubCopilotHookResult.PreToolUse,
                GitHubCopilotHookResult.McpMetadata,
                GitHubCopilotHookResult.PostToolUse,
                GitHubCopilotHookResult.Prompt,
                GitHubCopilotHookResult.SessionStart,
                GitHubCopilotHookResult.SessionEnd,
                GitHubCopilotHookResult.AgentStop {
    /**
     * Returns a result that preserves the SDK's current behavior.
     *
     * @return no-change result
     */
    static GitHubCopilotHookResult unchanged() {
        return NoChange.INSTANCE;
    }

    /**
     * Represents no hook output.
     */
    enum NoChange implements GitHubCopilotHookResult {
        /** Singleton no-change value. */
        INSTANCE
    }

    /**
     * Selects the official pre-tool permission decision.
     */
    enum ToolPermission {
        /** Allows the tool. */
        ALLOW("allow"),
        /** Denies the tool. */
        DENY("deny"),
        /** Requests a user decision. */
        ASK("ask");

        private final String sdkValue;

        ToolPermission(String sdkValue) {
            this.sdkValue = sdkValue;
        }

        String sdkValue() {
            return sdkValue;
        }
    }

    /**
     * Controls metadata on an outgoing MCP tool call.
     */
    enum McpMetadataAction {
        /** Preserves existing metadata by returning no SDK hook output. */
        KEEP,
        /** Replaces existing metadata. */
        REPLACE,
        /** Removes existing metadata. */
        REMOVE
    }

    /**
     * Controls a pre-tool-use hook.
     *
     * @param permission official permission decision
     * @param reason optional decision reason
     * @param modifiedArguments optional replacement arguments
     * @param additionalContext optional model context
     * @param suppressOutput optional output suppression
     */
    record PreToolUse(
            ToolPermission permission,
            String reason,
            StateValue modifiedArguments,
            String additionalContext,
            Boolean suppressOutput)
            implements GitHubCopilotHookResult {
        /** Creates a validated pre-tool result. */
        public PreToolUse {
            if (permission == null) {
                throw new IllegalArgumentException("permission is required.");
            }
        }
    }

    /**
     * Controls outgoing MCP metadata.
     *
     * @param action metadata action
     * @param metadata replacement metadata for {@link McpMetadataAction#REPLACE}
     */
    record McpMetadata(McpMetadataAction action, StateValue.ObjectValue metadata) implements GitHubCopilotHookResult {
        /** Creates a validated MCP metadata result. */
        public McpMetadata {
            if (action == null || (action == McpMetadataAction.REPLACE) != (metadata != null)) {
                throw new IllegalArgumentException("REPLACE requires metadata; KEEP and REMOVE must omit it.");
            }
        }
    }

    /**
     * Controls a successful post-tool-use hook.
     *
     * @param modifiedResult optional replacement result
     * @param additionalContext optional model context
     * @param suppressOutput optional output suppression
     */
    record PostToolUse(StateValue modifiedResult, String additionalContext, Boolean suppressOutput)
            implements GitHubCopilotHookResult {}

    /**
     * Controls a user-prompt-submitted hook.
     *
     * @param modifiedPrompt optional replacement prompt
     * @param additionalContext optional model context
     * @param suppressOutput optional output suppression
     */
    record Prompt(String modifiedPrompt, String additionalContext, Boolean suppressOutput)
            implements GitHubCopilotHookResult {}

    /**
     * Controls a session-start hook.
     *
     * @param additionalContext optional context
     * @param modifiedConfig immutable replacement configuration fields
     */
    record SessionStart(String additionalContext, Map<String, StateValue> modifiedConfig)
            implements GitHubCopilotHookResult {
        /** Creates and defensively copies a session-start result. */
        public SessionStart {
            modifiedConfig = modifiedConfig == null ? Map.of() : Map.copyOf(modifiedConfig);
        }
    }

    /**
     * Controls a session-end hook.
     *
     * @param suppressOutput optional output suppression
     * @param cleanupActions immutable cleanup actions
     * @param sessionSummary optional summary
     */
    record SessionEnd(Boolean suppressOutput, List<String> cleanupActions, String sessionSummary)
            implements GitHubCopilotHookResult {
        /** Creates and defensively copies a session-end result. */
        public SessionEnd {
            cleanupActions = cleanupActions == null ? List.of() : List.copyOf(cleanupActions);
        }
    }

    /**
     * Controls an agent-stop hook.
     *
     * @param block whether to keep the agent running
     * @param reason required follow-up instruction when blocked
     */
    record AgentStop(boolean block, String reason) implements GitHubCopilotHookResult {
        /** Creates a validated agent-stop result. */
        public AgentStop {
            if (block && (reason == null || reason.isBlank())) {
                throw new IllegalArgumentException("reason is required when agent stop is blocked.");
            }
            if (!block && reason != null) {
                throw new IllegalArgumentException("reason must be absent when agent stop is allowed.");
            }
        }
    }
}
