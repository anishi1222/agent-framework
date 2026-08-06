// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.foundry;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.AgentResponse;
import com.microsoft.agents.core.AgentResponseUpdate;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.tools.Tool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Provides a {@link ChatAgent} backed by a Microsoft Foundry model or existing agent reference.
 *
 * <p>This is a local Agent Framework execution wrapper. For an existing Foundry agent, its
 * server-side definition owns model, instructions, and tool declarations; local function tools are
 * used only to dispatch function calls returned by that definition.
 */
public final class FoundryAgent implements Agent<Void> {
    private final FoundryChatClient chatClient;

    private final ChatAgent delegate;

    private FoundryAgent(
            FoundryChatClient chatClient,
            AgentMetadata metadata,
            ChatOptions chatOptions,
            Collection<? extends Tool> tools) {
        this.chatClient = chatClient;
        delegate = new ChatAgent(chatClient, metadata, chatOptions, tools);
    }

    /**
     * Creates a Foundry agent builder.
     *
     * @return agent builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the owned Foundry chat client.
     *
     * @return chat client
     */
    public FoundryChatClient chatClient() {
        return chatClient;
    }

    @Override
    public AgentMetadata metadata() {
        return delegate.metadata();
    }

    @Override
    public RunHandle<AgentResponse<Void>> startRun(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return delegate.startRun(messages, options, cancellation);
    }

    @Override
    public Flow.Publisher<AgentResponseUpdate> runStreaming(
            List<Message> messages, RunOptions options, RunCancellation cancellation) {
        return delegate.runStreaming(messages, options, cancellation);
    }

    /**
     * Closes the local agent runtime and its owned Foundry chat client.
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            chatClient.close();
        }
    }

    /** Builds immutable {@link FoundryAgent} instances. */
    public static final class Builder {
        private FoundryChatClientOptions options;

        private FoundryTransport transport;

        private boolean closeTransport;

        private Collection<? extends Tool> tools = List.of();

        private ChatOptions chatOptions = ChatOptions.empty();

        private String name;

        private String description;

        private Builder() {}

        /**
         * Sets required Foundry client options.
         *
         * @param options client options
         * @return this builder
         */
        public Builder options(FoundryChatClientOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Injects a caller-owned deterministic transport.
         *
         * @param transport transport boundary
         * @return this builder
         */
        public Builder transport(FoundryTransport transport) {
            return transport(transport, false);
        }

        /**
         * Injects a transport and selects whether ownership transfers to the agent.
         *
         * @param transport transport boundary
         * @param closeTransport whether the agent closes the transport
         * @return this builder
         */
        public Builder transport(FoundryTransport transport, boolean closeTransport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            this.closeTransport = closeTransport;
            return this;
        }

        /**
         * Sets local function tools.
         *
         * @param tools tools executed by the local {@link ChatAgent}
         * @return this builder
         */
        public Builder tools(Collection<? extends Tool> tools) {
            this.tools = Objects.requireNonNull(tools, "tools");
            return this;
        }

        /**
         * Sets provider-neutral chat defaults.
         *
         * @param chatOptions chat options
         * @return this builder
         */
        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = Objects.requireNonNull(chatOptions, "chatOptions");
            return this;
        }

        /**
         * Sets an optional display name.
         *
         * @param name display name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets an optional description.
         *
         * @param description description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Creates the Foundry agent.
         *
         * @return configured agent
         */
        public FoundryAgent build() {
            FoundryChatClientOptions builtOptions = Objects.requireNonNull(options, "options");
            FoundryChatClient.Builder clientBuilder =
                    FoundryChatClient.builder().options(builtOptions);
            if (transport != null) {
                clientBuilder.transport(transport, closeTransport);
            }
            FoundryChatClient client = clientBuilder.build();
            ArrayList<Tool> normalizedTools = new ArrayList<>(tools.size());
            for (Tool tool : tools) {
                normalizedTools.add(Objects.requireNonNull(tool, "tool"));
            }
            AgentMetadata metadata = builtOptions
                    .agentName()
                    .map(agentName -> new AgentMetadata(agentName, name == null ? agentName : name, description))
                    .orElseGet(() -> AgentMetadata.create(name, description));
            return new FoundryAgent(client, metadata, chatOptions, List.copyOf(normalizedTools));
        }
    }
}
