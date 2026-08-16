// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines immutable official SDK session configuration with deny-by-default permissions.
 */
public final class GitHubCopilotSessionConfig {
    private final String sessionId;

    private final String model;

    private final String reasoningEffort;

    private final boolean streaming;

    private final GitHubCopilotSystemMessage systemMessage;

    private final List<GitHubCopilotTool> tools;

    private final List<String> allowedBuiltInTools;

    private final List<String> excludedTools;

    private final Map<String, GitHubCopilotMCPServerConfig> mcpServers;

    private final List<GitHubCopilotCustomAgent> customAgents;

    private final List<Path> skillDirectories;

    private final Set<String> disabledSkills;

    private final GitHubCopilotInfiniteSessionConfig infiniteSession;

    private final GitHubCopilotProviderConfig provider;

    private final GitHubCopilotPermissionHandler permissionHandler;

    private final GitHubCopilotUserInputHandler userInputHandler;

    private final Map<GitHubCopilotHookType, GitHubCopilotHook> hooks;

    private final boolean enableSessionStore;

    private GitHubCopilotSessionConfig(Builder builder) {
        sessionId = optional(builder.sessionId);
        model = optional(builder.model);
        reasoningEffort = optional(builder.reasoningEffort);
        streaming = builder.streaming;
        systemMessage = builder.systemMessage;
        tools = List.copyOf(builder.tools);
        allowedBuiltInTools = copyNames(builder.allowedBuiltInTools, "allowedBuiltInTools");
        excludedTools = copyNames(builder.excludedTools, "excludedTools");
        mcpServers = Map.copyOf(builder.mcpServers);
        customAgents = List.copyOf(builder.customAgents);
        skillDirectories = canonicalDirectories(builder.skillDirectories);
        disabledSkills = Set.copyOf(copyNames(builder.disabledSkills, "disabledSkills"));
        infiniteSession = builder.infiniteSession;
        provider = builder.provider;
        permissionHandler = Objects.requireNonNull(builder.permissionHandler, "permissionHandler");
        userInputHandler = Objects.requireNonNull(builder.userInputHandler, "userInputHandler");
        hooks = Map.copyOf(builder.hooks);
        enableSessionStore = builder.enableSessionStore;
        validateUniqueNames();
    }

    /**
     * Creates a session builder.
     *
     * @return session builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the optional caller-selected session identity. */
    public String sessionId() {
        return sessionId;
    }

    /** Returns the optional model identifier. */
    public String model() {
        return model;
    }

    /** Returns the optional reasoning effort. */
    public String reasoningEffort() {
        return reasoningEffort;
    }

    /** Returns whether incremental events are requested. */
    public boolean streaming() {
        return streaming;
    }

    /** Returns the optional system-message override. */
    public GitHubCopilotSystemMessage systemMessage() {
        return systemMessage;
    }

    /** Returns immutable custom tools. */
    public List<GitHubCopilotTool> tools() {
        return tools;
    }

    /** Returns explicitly allowed built-in tool names. */
    public List<String> allowedBuiltInTools() {
        return allowedBuiltInTools;
    }

    /** Returns explicitly excluded tool names. */
    public List<String> excludedTools() {
        return excludedTools;
    }

    /** Returns immutable named MCP configurations. */
    public Map<String, GitHubCopilotMCPServerConfig> mcpServers() {
        return mcpServers;
    }

    /** Returns immutable custom-agent configurations. */
    public List<GitHubCopilotCustomAgent> customAgents() {
        return customAgents;
    }

    /** Returns canonical skill directories. */
    public List<Path> skillDirectories() {
        return skillDirectories;
    }

    /** Returns explicitly disabled skill names. */
    public Set<String> disabledSkills() {
        return disabledSkills;
    }

    /** Returns optional infinite-session configuration. */
    public GitHubCopilotInfiniteSessionConfig infiniteSession() {
        return infiniteSession;
    }

    /** Returns optional BYOK provider configuration. */
    public GitHubCopilotProviderConfig provider() {
        return provider;
    }

    /** Returns the required permission handler. */
    public GitHubCopilotPermissionHandler permissionHandler() {
        return permissionHandler;
    }

    /** Returns the required user-input handler. */
    public GitHubCopilotUserInputHandler userInputHandler() {
        return userInputHandler;
    }

    /** Returns immutable official SDK hook handlers by hook point. */
    public Map<GitHubCopilotHookType, GitHubCopilotHook> hooks() {
        return hooks;
    }

    /**
     * Returns whether external CLI session-store indexing is enabled.
     *
     * @return session-store opt-in
     */
    public boolean enableSessionStore() {
        return enableSessionStore;
    }

    private void validateUniqueNames() {
        unique(tools.stream().map(GitHubCopilotTool::name).toList(), "tool");
        unique(customAgents.stream().map(GitHubCopilotCustomAgent::name).toList(), "custom agent");
        mcpServers.keySet().forEach(name -> required(name, "MCP server name"));
    }

    private static void unique(List<String> names, String kind) {
        if (new LinkedHashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException(kind + " names must be unique.");
        }
    }

    private static List<Path> canonicalDirectories(List<Path> values) {
        ArrayList<Path> result = new ArrayList<>();
        for (Path value : values) {
            try {
                Path canonical = value.toRealPath();
                if (!Files.isDirectory(canonical)) {
                    throw new IllegalArgumentException("skillDirectories must contain directories.");
                }
                result.add(canonical);
            } catch (IOException exception) {
                throw new IllegalArgumentException("skillDirectories contains an unresolved path.", exception);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> copyNames(Iterable<String> values, String name) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            result.add(required(value, name + " element"));
        }
        return List.copyOf(result);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    /** Builds immutable {@link GitHubCopilotSessionConfig} instances. */
    public static final class Builder {
        private String sessionId;

        private String model;

        private String reasoningEffort;

        private boolean streaming = true;

        private GitHubCopilotSystemMessage systemMessage;

        private final List<GitHubCopilotTool> tools = new ArrayList<>();

        private final List<String> allowedBuiltInTools = new ArrayList<>();

        private final List<String> excludedTools = new ArrayList<>();

        private final Map<String, GitHubCopilotMCPServerConfig> mcpServers = new LinkedHashMap<>();

        private final List<GitHubCopilotCustomAgent> customAgents = new ArrayList<>();

        private final List<Path> skillDirectories = new ArrayList<>();

        private final Set<String> disabledSkills = new LinkedHashSet<>();

        private GitHubCopilotInfiniteSessionConfig infiniteSession;

        private GitHubCopilotProviderConfig provider;

        private GitHubCopilotPermissionHandler permissionHandler = GitHubCopilotPermissionHandler.denyAll();

        private GitHubCopilotUserInputHandler userInputHandler = GitHubCopilotUserInputHandler.declineAll();

        private final Map<GitHubCopilotHookType, GitHubCopilotHook> hooks = new LinkedHashMap<>();

        private boolean enableSessionStore;

        private Builder() {}

        /** Sets a caller-selected session identity. */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Sets the model identifier. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the reasoning effort. */
        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        /** Enables or disables incremental upstream events. */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** Sets a system-message override. */
        public Builder systemMessage(GitHubCopilotSystemMessage systemMessage) {
            this.systemMessage = Objects.requireNonNull(systemMessage, "systemMessage");
            return this;
        }

        /** Adds a caller-declared custom tool. */
        public Builder tool(GitHubCopilotTool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        /** Explicitly allows one built-in tool name. */
        public Builder allowBuiltInTool(String name) {
            allowedBuiltInTools.add(required(name, "name"));
            return this;
        }

        /** Explicitly excludes one tool name. */
        public Builder excludeTool(String name) {
            excludedTools.add(required(name, "name"));
            return this;
        }

        /** Adds one named MCP server. */
        public Builder mcpServer(String name, GitHubCopilotMCPServerConfig config) {
            String key = required(name, "name");
            if (mcpServers.putIfAbsent(key, Objects.requireNonNull(config, "config")) != null) {
                throw new IllegalArgumentException("Duplicate MCP server name: " + key);
            }
            return this;
        }

        /** Adds a custom agent. */
        public Builder customAgent(GitHubCopilotCustomAgent customAgent) {
            customAgents.add(Objects.requireNonNull(customAgent, "customAgent"));
            return this;
        }

        /** Adds a canonical skill directory. */
        public Builder skillDirectory(Path directory) {
            skillDirectories.add(Objects.requireNonNull(directory, "directory"));
            return this;
        }

        /** Disables a named skill. */
        public Builder disableSkill(String name) {
            disabledSkills.add(required(name, "name"));
            return this;
        }

        /** Sets infinite-session behavior. */
        public Builder infiniteSession(GitHubCopilotInfiniteSessionConfig infiniteSession) {
            this.infiniteSession = Objects.requireNonNull(infiniteSession, "infiniteSession");
            return this;
        }

        /** Sets a documented BYOK provider. */
        public Builder provider(GitHubCopilotProviderConfig provider) {
            this.provider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        /** Replaces the deny-by-default permission handler. */
        public Builder permissionHandler(GitHubCopilotPermissionHandler permissionHandler) {
            this.permissionHandler = Objects.requireNonNull(permissionHandler, "permissionHandler");
            return this;
        }

        /** Replaces the decline-by-default input handler. */
        public Builder userInputHandler(GitHubCopilotUserInputHandler userInputHandler) {
            this.userInputHandler = Objects.requireNonNull(userInputHandler, "userInputHandler");
            return this;
        }

        /**
         * Registers one handler for a stable official SDK hook point.
         *
         * @param type hook point
         * @param hook handler
         * @return this builder
         */
        public Builder hook(GitHubCopilotHookType type, GitHubCopilotHook hook) {
            GitHubCopilotHookType key = Objects.requireNonNull(type, "type");
            if (hooks.putIfAbsent(key, Objects.requireNonNull(hook, "hook")) != null) {
                throw new IllegalArgumentException("Duplicate hook handler: " + key);
            }
            return this;
        }

        /** Enables or disables the external CLI session-store index. */
        public Builder enableSessionStore(boolean enableSessionStore) {
            this.enableSessionStore = enableSessionStore;
            return this;
        }

        /**
         * Creates immutable session configuration.
         *
         * @return session configuration
         */
        public GitHubCopilotSessionConfig build() {
            return new GitHubCopilotSessionConfig(this);
        }
    }
}
