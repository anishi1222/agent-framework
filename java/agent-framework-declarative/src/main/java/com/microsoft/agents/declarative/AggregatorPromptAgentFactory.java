// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.agents.Agent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Selects the first ordered prompt-agent factory that supports a definition. */
public final class AggregatorPromptAgentFactory extends PromptAgentFactory {
    private final List<PromptAgentFactory> factories;

    /**
     * Creates an ordered aggregate.
     *
     * @param factories non-empty ordered factories
     */
    public AggregatorPromptAgentFactory(PromptAgentFactory... factories) {
        Objects.requireNonNull(factories, "factories");
        if (factories.length == 0) {
            throw new DeclarativeAgentValidationException("At least one prompt-agent factory is required.");
        }
        Arrays.stream(factories).forEach(factory -> Objects.requireNonNull(factory, "factory"));
        this.factories = List.copyOf(Arrays.asList(factories));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Agent<?>> tryCreate(PromptAgentDefinition definition) {
        PromptAgentDefinition checked = Objects.requireNonNull(definition, "definition");
        for (PromptAgentFactory factory : factories) {
            Optional<Agent<?>> agent = Objects.requireNonNull(factory.tryCreate(checked), "factory.tryCreate result");
            if (agent.isPresent()) {
                return agent;
            }
        }
        return Optional.empty();
    }
}
