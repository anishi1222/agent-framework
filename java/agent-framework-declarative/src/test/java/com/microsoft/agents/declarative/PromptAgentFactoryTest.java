// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.agents.ChatClient;
import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.Tool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PromptAgentFactoryTest {
    @Test
    void chatClientFactory_shouldCreateConfiguredChatAgentDeterministically() {
        // Arrange
        ChatClient defaultClient = mock(ChatClient.class);
        ChatClient responsesClient = mock(ChatClient.class);
        Tool tool = mock(Tool.class);
        ContextProvider provider = mock(ContextProvider.class);
        when(provider.id()).thenReturn("memory");
        PromptAgentDefinition definition = definition("OpenAI", "Responses", List.of("weather"), List.of("memory"));
        ChatClientPromptAgentFactory factory = new ChatClientPromptAgentFactory(
                ChatClientRegistry.of(Map.of("OpenAI", defaultClient, "OpenAI.Responses", responsesClient)),
                ToolRegistry.of(Map.of("weather", tool)),
                ContextProviderRegistry.of(Map.of("memory", provider)));

        // Act
        ChatAgent first = (ChatAgent) factory.create(definition);
        ChatAgent second = (ChatAgent) factory.create(definition);

        // Assert
        assertThat(first.chatClient()).isSameAs(responsesClient);
        assertThat(first.id()).isEqualTo("support");
        assertThat(first.name()).isEqualTo("Support Agent");
        assertThat(first.description()).isEqualTo("Answers support questions.");
        assertThat(first.tools()).containsExactly(tool);
        assertThat(first.chatOptions().model()).isEqualTo("gpt-4.1");
        assertThat(first.chatOptions().temperature()).isEqualTo(0.25);
        assertThat(first.chatOptions().topP()).isEqualTo(0.9);
        assertThat(first.chatOptions().maxTokens()).isEqualTo(512);
        assertThat(first.chatOptions().stop()).containsExactly("DONE");
        assertThat(first.chatOptions().seed()).isEqualTo(42L);
        assertThat(first.chatOptions().frequencyPenalty()).isEqualTo(0.1);
        assertThat(first.chatOptions().presencePenalty()).isEqualTo(-0.2);
        assertThat(first.chatOptions().allowMultipleToolCalls()).isTrue();
        assertThat(first.chatOptions().instructions()).isEqualTo("Be concise.\n\nCite sources.");
        assertThat(first.chatOptions().metadata()).containsEntry("tenant", StateValue.string("north"));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.chatOptions()).isEqualTo(first.chatOptions());
    }

    @Test
    void chatClientFactory_shouldFallBackToProviderAndDefaultProvider() {
        ChatClient client = mock(ChatClient.class);
        ChatClientRegistry registry = ChatClientRegistry.of(Map.of("OpenAI", client));
        ChatClientPromptAgentFactory fallback = new ChatClientPromptAgentFactory(registry);
        ChatClientPromptAgentFactory defaulted = new ChatClientPromptAgentFactory(
                registry, ToolRegistry.empty(), ContextProviderRegistry.empty(), "OpenAI");

        ChatAgent providerFallback =
                (ChatAgent) fallback.create(definition("OpenAI", "Responses", List.of(), List.of()));
        ChatAgent defaultProvider = (ChatAgent) defaulted.create(definition(null, null, List.of(), List.of()));

        assertThat(providerFallback.chatClient()).isSameAs(client);
        assertThat(defaultProvider.chatClient()).isSameAs(client);
    }

    @Test
    void chatClientFactory_shouldSupportOneCallerOwnedClientWithoutProviderSelection() {
        ChatClient client = mock(ChatClient.class);
        ChatClientPromptAgentFactory factory = new ChatClientPromptAgentFactory(client);

        ChatAgent agent = (ChatAgent) factory.create(definition(null, null, List.of(), List.of()));

        assertThat(agent.chatClient()).isSameAs(client);
    }

    @Test
    void chatClientFactory_shouldReportMissingReferences() {
        ChatClientPromptAgentFactory factory = new ChatClientPromptAgentFactory(
                ChatClientRegistry.of(Map.of("OpenAI", mock(ChatClient.class))),
                ToolRegistry.empty(),
                ContextProviderRegistry.empty());

        assertThatThrownBy(() -> factory.create(definition("OpenAI", null, List.of("missing"), List.of())))
                .isInstanceOf(DeclarativeAgentValidationException.class)
                .hasMessageContaining("missing tool 'missing'");
        assertThatThrownBy(() -> factory.create(definition("OpenAI", null, List.of(), List.of("missing"))))
                .isInstanceOf(DeclarativeAgentValidationException.class)
                .hasMessageContaining("missing context provider 'missing'");
    }

    @Test
    void chatClientFactory_shouldReturnEmptyWhenNoClientSupportsModel() {
        ChatClientPromptAgentFactory factory = new ChatClientPromptAgentFactory(ChatClientRegistry.empty());
        PromptAgentDefinition definition = definition("Unknown", null, List.of(), List.of());

        assertThat(factory.tryCreate(definition)).isEmpty();
        assertThatThrownBy(() -> factory.create(definition))
                .isInstanceOf(DeclarativeAgentValidationException.class)
                .hasMessageContaining("No prompt-agent factory supports");
    }

    @Test
    void aggregator_shouldSelectFirstSupportingFactory() {
        Agent<?> expected = mock(Agent.class);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        PromptAgentFactory unsupported = new PromptAgentFactory() {
            @Override
            public Optional<Agent<?>> tryCreate(PromptAgentDefinition definition) {
                firstCalls.incrementAndGet();
                return Optional.empty();
            }
        };
        PromptAgentFactory supported = new PromptAgentFactory() {
            @Override
            public Optional<Agent<?>> tryCreate(PromptAgentDefinition definition) {
                secondCalls.incrementAndGet();
                return Optional.of(expected);
            }
        };
        PromptAgentFactory neverReached = new PromptAgentFactory() {
            @Override
            public Optional<Agent<?>> tryCreate(PromptAgentDefinition definition) {
                throw new AssertionError("Factory order was not respected.");
            }
        };
        AggregatorPromptAgentFactory aggregator =
                new AggregatorPromptAgentFactory(unsupported, supported, neverReached);

        Agent<?> actual = aggregator.create(definition("OpenAI", null, List.of(), List.of()));

        assertThat(actual).isSameAs(expected);
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void aggregator_shouldRejectEmptyOrNullFactories() {
        assertThatThrownBy(AggregatorPromptAgentFactory::new)
                .isInstanceOf(DeclarativeAgentValidationException.class)
                .hasMessageContaining("At least one");
        assertThatThrownBy(() -> new AggregatorPromptAgentFactory((PromptAgentFactory) null))
                .isInstanceOf(NullPointerException.class);
    }

    private static PromptAgentDefinition definition(
            String provider, String apiType, List<String> tools, List<String> contextProviders) {
        return new PromptAgentDefinition(
                "Prompt",
                "support",
                "Support Agent",
                "Answers support questions.",
                Map.of("tenant", StateValue.string("north")),
                new PromptModelDefinition(
                        "gpt-4.1",
                        provider,
                        apiType,
                        new PromptModelOptions(0.1, 512, -0.2, 42L, 0.25, 0.9, List.of("DONE"), true)),
                tools,
                contextProviders,
                "Be concise.",
                "Cite sources.");
    }
}
