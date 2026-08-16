// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configures one framework {@link Agent} exposed as an MCP tool.
 */
public final class MCPAgentTool {
    private final Agent<?> agent;

    private final String name;

    private final String description;

    private final String argumentName;

    private final StateValue.ObjectValue inputSchema;

    private final StateValue.ObjectValue outputSchema;

    private MCPAgentTool(Builder builder) {
        agent = Objects.requireNonNull(builder.agent, "agent");
        String defaultName = agent.name() == null ? agent.id() : agent.name();
        name = HostingMCPNames.normalize(builder.name == null ? defaultName : builder.name);
        description = builder.description == null
                ? Objects.requireNonNullElse(agent.description(), "Runs the hosted agent.")
                : builder.description;
        argumentName = HostingMCPValidation.nonBlank(builder.argumentName, "argumentName");
        inputSchema = defaultInputSchema(argumentName, name);
        outputSchema = defaultOutputSchema();
    }

    /**
     * Creates a builder for an agent.
     *
     * @param agent hosted agent; ownership remains with the caller
     * @return agent-tool builder
     */
    public static Builder builder(Agent<?> agent) {
        return new Builder(agent);
    }

    /**
     * Returns the caller-owned agent.
     *
     * @return agent
     */
    public Agent<?> agent() {
        return agent;
    }

    /**
     * Returns the normalized MCP tool name.
     *
     * @return tool name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the tool description.
     *
     * @return description
     */
    public String description() {
        return description;
    }

    /**
     * Returns the required text argument name.
     *
     * @return argument name
     */
    public String argumentName() {
        return argumentName;
    }

    /**
     * Returns the input schema.
     *
     * @return JSON-shaped schema
     */
    public StateValue.ObjectValue inputSchema() {
        return inputSchema;
    }

    /**
     * Returns the structured output schema.
     *
     * @return JSON-shaped schema
     */
    public StateValue.ObjectValue outputSchema() {
        return outputSchema;
    }

    /** Builds an agent-backed MCP tool. */
    public static final class Builder {
        private final Agent<?> agent;

        private String name;

        private String description;

        private String argumentName = "task";

        private Builder(Agent<?> agent) {
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        /**
         * Overrides the MCP tool name.
         *
         * @param name name before safe normalization
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Overrides the tool description.
         *
         * @param description precise description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the required user-text argument name.
         *
         * @param name argument name
         * @return this builder
         */
        public Builder argumentName(String name) {
            argumentName = name;
            return this;
        }

        /**
         * Creates the immutable agent tool.
         *
         * @return agent tool
         */
        public MCPAgentTool build() {
            return new MCPAgentTool(this);
        }
    }

    private static StateValue.ObjectValue defaultInputSchema(String argumentName, String toolName) {
        LinkedHashMap<String, StateValue> argument = new LinkedHashMap<>();
        argument.put("type", StateValue.string("string"));
        argument.put("description", StateValue.string("Task for " + toolName + "."));
        return StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of(argumentName, StateValue.object(argument))),
                "required",
                StateValue.array(List.of(StateValue.string(argumentName))),
                "additionalProperties",
                StateValue.bool(false)));
    }

    private static StateValue.ObjectValue defaultOutputSchema() {
        return StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of("text", StateValue.object(Map.of("type", StateValue.string("string"))))),
                "required",
                StateValue.array(List.of(StateValue.string("text"))),
                "additionalProperties",
                StateValue.bool(false)));
    }
}
