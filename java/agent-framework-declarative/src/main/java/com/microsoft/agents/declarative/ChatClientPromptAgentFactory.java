// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.AgentMetadata;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.core.ChatOptions;
import com.microsoft.agents.tools.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates {@link ChatAgent} instances using caller-owned chat-client, tool, and context-provider
 * registries.
 *
 * <p>When {@code apiType} is present, lookup first uses {@code provider.apiType} and then falls back
 * to {@code provider}. The selected clients, tools, and providers remain caller-owned.
 */
public final class ChatClientPromptAgentFactory extends PromptAgentFactory {
    private final ChatClientRegistry chatClients;

    private final ToolRegistry tools;

    private final ContextProviderRegistry contextProviders;

    private final String defaultProvider;

    /**
     * Creates a factory with no tools, context providers, or default provider.
     *
     * @param chatClients caller-owned chat-client registry
     */
    public ChatClientPromptAgentFactory(ChatClientRegistry chatClients) {
        this(chatClients, ToolRegistry.empty(), ContextProviderRegistry.empty(), null);
    }

    /**
     * Creates a factory backed by one caller-owned chat client.
     *
     * @param chatClient caller-owned provider-neutral chat client
     */
    public ChatClientPromptAgentFactory(ChatClient chatClient) {
        this(ChatClientRegistry.fixed(chatClient), ToolRegistry.empty(), ContextProviderRegistry.empty(), "default");
    }

    /**
     * Creates a factory backed by one caller-owned chat client and explicit registries.
     *
     * @param chatClient caller-owned provider-neutral chat client
     * @param tools caller-owned tool registry
     * @param contextProviders caller-owned context-provider registry
     */
    public ChatClientPromptAgentFactory(
            ChatClient chatClient, ToolRegistry tools, ContextProviderRegistry contextProviders) {
        this(ChatClientRegistry.fixed(chatClient), tools, contextProviders, "default");
    }

    /**
     * Creates a factory with explicit registries and no default provider.
     *
     * @param chatClients caller-owned chat-client registry
     * @param tools caller-owned tool registry
     * @param contextProviders caller-owned context-provider registry
     */
    public ChatClientPromptAgentFactory(
            ChatClientRegistry chatClients, ToolRegistry tools, ContextProviderRegistry contextProviders) {
        this(chatClients, tools, contextProviders, null);
    }

    /**
     * Creates a factory with explicit registries and an optional default provider key.
     *
     * @param chatClients caller-owned chat-client registry
     * @param tools caller-owned tool registry
     * @param contextProviders caller-owned context-provider registry
     * @param defaultProvider optional provider key used when a definition omits {@code model.provider}
     */
    public ChatClientPromptAgentFactory(
            ChatClientRegistry chatClients,
            ToolRegistry tools,
            ContextProviderRegistry contextProviders,
            String defaultProvider) {
        this.chatClients = Objects.requireNonNull(chatClients, "chatClients");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.contextProviders = Objects.requireNonNull(contextProviders, "contextProviders");
        this.defaultProvider = AgentDefinitionValidation.optionalNonBlank(defaultProvider, "defaultProvider");
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Agent<?>> tryCreate(PromptAgentDefinition definition) {
        PromptAgentDefinition checked = Objects.requireNonNull(definition, "definition");
        Optional<ChatClient> client = resolveChatClient(checked.model());
        if (client.isEmpty()) {
            return Optional.empty();
        }

        PromptModelOptions modelOptions = checked.model().options();
        ChatOptions.Builder options = ChatOptions.builder()
                .model(checked.model().id())
                .stop(modelOptions.stopSequences())
                .metadata(checked.metadata());
        if (modelOptions.frequencyPenalty() != null) {
            options.frequencyPenalty(modelOptions.frequencyPenalty());
        }
        if (modelOptions.maxOutputTokens() != null) {
            options.maxTokens(modelOptions.maxOutputTokens());
        }
        if (modelOptions.presencePenalty() != null) {
            options.presencePenalty(modelOptions.presencePenalty());
        }
        if (modelOptions.seed() != null) {
            options.seed(modelOptions.seed());
        }
        if (modelOptions.temperature() != null) {
            options.temperature(modelOptions.temperature());
        }
        if (modelOptions.topP() != null) {
            options.topP(modelOptions.topP());
        }
        if (modelOptions.allowMultipleToolCalls() != null) {
            options.allowMultipleToolCalls(modelOptions.allowMultipleToolCalls());
        }
        String instructions = checked.combinedInstructions();
        if (instructions != null) {
            options.instructions(instructions);
        }

        AgentMetadata metadata = new AgentMetadata(
                checked.name(),
                checked.displayName() == null ? checked.name() : checked.displayName(),
                checked.description());
        ChatAgent agent = new ChatAgent(
                client.orElseThrow(),
                metadata,
                options.build(),
                resolveTools(checked),
                resolveContextProviders(checked),
                List.of(),
                List.of(),
                List.of(),
                null);
        return Optional.of(agent);
    }

    private Optional<ChatClient> resolveChatClient(PromptModelDefinition model) {
        String provider = model.provider() == null ? defaultProvider : model.provider();
        if (provider == null) {
            return Optional.empty();
        }
        if (model.apiType() != null) {
            Optional<ChatClient> specialized = chatClients.find(provider + "." + model.apiType());
            if (specialized.isPresent()) {
                return specialized;
            }
        }
        return chatClients.find(provider);
    }

    private List<Tool> resolveTools(PromptAgentDefinition definition) {
        ArrayList<Tool> resolved = new ArrayList<>(definition.tools().size());
        for (String reference : definition.tools()) {
            resolved.add(tools.find(reference)
                    .orElseThrow(() -> new DeclarativeAgentValidationException(
                            "Agent '" + definition.name() + "' references missing tool '" + reference + "'.")));
        }
        return List.copyOf(resolved);
    }

    private List<ContextProvider> resolveContextProviders(PromptAgentDefinition definition) {
        ArrayList<ContextProvider> resolved =
                new ArrayList<>(definition.contextProviders().size());
        for (String reference : definition.contextProviders()) {
            resolved.add(contextProviders
                    .find(reference)
                    .orElseThrow(() -> new DeclarativeAgentValidationException("Agent '"
                            + definition.name()
                            + "' references missing context provider '"
                            + reference
                            + "'.")));
        }
        return List.copyOf(resolved);
    }
}
