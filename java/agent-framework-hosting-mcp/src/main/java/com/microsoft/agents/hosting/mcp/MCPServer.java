// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.protocols.mcp.MCPLimits;
import com.microsoft.agents.tools.FunctionTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines an immutable MCP server exposing framework tools, agents, prompts, and resources.
 *
 * <p>The definition owns no caller-supplied tools or agents. Each started transport owns its SDK
 * server, concurrency controls, and virtual-thread executor. MCP tool calls are terminal results;
 * this adapter makes no cross-process resume or durable-task claim.
 */
public final class MCPServer {
    private final String name;

    private final String version;

    private final String instructions;

    private final Duration callTimeout;

    private final MCPLimits limits;

    private final List<FunctionTool> tools;

    private final List<MCPAgentTool> agents;

    private final List<MCPServerPrompt> prompts;

    private final List<MCPServerResource> resources;

    private MCPServer(Builder builder) {
        name = HostingMCPValidation.nonBlank(builder.name, "name");
        version = HostingMCPValidation.nonBlank(builder.version, "version");
        instructions = builder.instructions == null ? "" : builder.instructions;
        callTimeout = HostingMCPValidation.positive(builder.callTimeout, "callTimeout");
        limits = Objects.requireNonNull(builder.limits, "limits");
        tools = List.copyOf(builder.tools);
        agents = List.copyOf(builder.agents);
        prompts = List.copyOf(builder.prompts);
        resources = List.copyOf(builder.resources);
        validateCounts();
        validateNames();
    }

    /**
     * Creates a server builder.
     *
     * @param name discoverable server name without a version suffix
     * @param version server implementation version
     * @return server builder
     */
    public static Builder builder(String name, String version) {
        return new Builder(name, version);
    }

    /**
     * Starts an MCP server over the process standard streams.
     *
     * <p>Diagnostics are never written to stdout by this adapter.
     *
     * @return owned stdio server handle
     */
    public MCPServerHandle startStdio() {
        return startStdio(System.in, System.out);
    }

    /**
     * Starts an MCP server over caller-supplied streams.
     *
     * @param input newline-delimited JSON-RPC input
     * @param output newline-delimited JSON-RPC output
     * @return owned stdio server handle
     */
    public MCPServerHandle startStdio(InputStream input, OutputStream output) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        BoundedStdioServerTransportProvider provider =
                new BoundedStdioServerTransportProvider(McpJsonDefaults.getMapper(), input, output, limits);
        return MCPServerRuntime.startStdio(this, provider);
    }

    /**
     * Starts an embedded Streamable HTTP/SSE server.
     *
     * @param options secure HTTP host options
     * @return owned HTTP server
     */
    public MCPStreamableHTTPServer startStreamableHTTP(MCPStreamableHTTPServerOptions options) {
        return EmbeddedMCPStreamableHTTPServer.start(this, options);
    }

    String name() {
        return name;
    }

    String version() {
        return version;
    }

    String instructions() {
        return instructions;
    }

    Duration callTimeout() {
        return callTimeout;
    }

    MCPLimits limits() {
        return limits;
    }

    List<FunctionTool> tools() {
        return tools;
    }

    List<MCPAgentTool> agents() {
        return agents;
    }

    List<MCPServerPrompt> prompts() {
        return prompts;
    }

    List<MCPServerResource> resources() {
        return resources;
    }

    /** Builds an immutable MCP server definition. */
    public static final class Builder {
        private final String name;

        private final String version;

        private String instructions;

        private Duration callTimeout = Duration.ofSeconds(30);

        private MCPLimits limits = MCPLimits.defaults();

        private final List<FunctionTool> tools = new ArrayList<>();

        private final List<MCPAgentTool> agents = new ArrayList<>();

        private final List<MCPServerPrompt> prompts = new ArrayList<>();

        private final List<MCPServerResource> resources = new ArrayList<>();

        private Builder(String name, String version) {
            this.name = name;
            this.version = version;
        }

        /**
         * Sets concise instructions advertised during initialization.
         *
         * @param instructions server instructions
         * @return this builder
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * Sets the maximum duration for a tool, agent, prompt, or resource handler.
         *
         * @param timeout positive timeout
         * @return this builder
         */
        public Builder callTimeout(Duration timeout) {
            callTimeout = timeout;
            return this;
        }

        /**
         * Sets finite payload, nesting, collection, concurrency, and buffer limits.
         *
         * @param limits limits
         * @return this builder
         */
        public Builder limits(MCPLimits limits) {
            this.limits = limits;
            return this;
        }

        /**
         * Adds one caller-owned framework function tool.
         *
         * @param tool function tool
         * @return this builder
         */
        public Builder tool(FunctionTool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        /**
         * Adds caller-owned framework function tools.
         *
         * @param tools function tools
         * @return this builder
         */
        public Builder tools(List<? extends FunctionTool> tools) {
            this.tools.addAll(List.copyOf(tools));
            return this;
        }

        /**
         * Adds one caller-owned agent adapter.
         *
         * @param agent agent tool
         * @return this builder
         */
        public Builder agent(MCPAgentTool agent) {
            agents.add(Objects.requireNonNull(agent, "agent"));
            return this;
        }

        /**
         * Adds one hosted prompt.
         *
         * @param prompt prompt
         * @return this builder
         */
        public Builder prompt(MCPServerPrompt prompt) {
            prompts.add(Objects.requireNonNull(prompt, "prompt"));
            return this;
        }

        /**
         * Adds one hosted resource.
         *
         * @param resource resource
         * @return this builder
         */
        public Builder resource(MCPServerResource resource) {
            resources.add(Objects.requireNonNull(resource, "resource"));
            return this;
        }

        /**
         * Creates the immutable server definition.
         *
         * @return MCP server definition
         */
        public MCPServer build() {
            return new MCPServer(this);
        }
    }

    private void validateCounts() {
        int exposedTools = tools.size() + agents.size();
        if (exposedTools > limits.maxCollectionItems()
                || prompts.size() > limits.maxCollectionItems()
                || resources.size() > limits.maxCollectionItems()) {
            throw new ValidationException("MCP server registration exceeds the configured collection limit.");
        }
    }

    private void validateNames() {
        Map<String, String> exposed = new LinkedHashMap<>();
        tools.forEach(tool -> addName(exposed, HostingMCPNames.normalize(tool.name()), tool.name()));
        agents.forEach(agent -> addName(exposed, agent.name(), agent.name()));
        Map<String, Boolean> promptNames = new LinkedHashMap<>();
        prompts.forEach(prompt -> {
            if (promptNames.putIfAbsent(prompt.name(), Boolean.TRUE) != null) {
                throw new ValidationException("Duplicate MCP prompt name '" + prompt.name() + "'.");
            }
        });
        Map<java.net.URI, Boolean> resourceUris = new LinkedHashMap<>();
        resources.forEach(resource -> {
            java.net.URI uri = resource.descriptor().uri();
            if (resourceUris.putIfAbsent(uri, Boolean.TRUE) != null) {
                throw new ValidationException("Duplicate MCP resource URI '" + uri + "'.");
            }
        });
    }

    private static void addName(Map<String, String> exposed, String normalized, String original) {
        String previous = exposed.putIfAbsent(normalized, original);
        if (previous != null) {
            throw new ValidationException("MCP tool names '"
                    + previous
                    + "' and '"
                    + original
                    + "' normalize to the same exposed name '"
                    + normalized
                    + "'.");
        }
    }
}
