// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.agents.Agent;
import java.util.Objects;
import java.util.Optional;

/** Creates executable agents from immutable prompt-agent definitions. */
public abstract class PromptAgentFactory {
    /** Creates a prompt-agent factory. */
    protected PromptAgentFactory() {}

    /**
     * Creates an agent or fails when this factory does not support the definition.
     *
     * @param definition immutable prompt-agent definition
     * @return executable provider-neutral agent
     * @throws DeclarativeAgentValidationException when no implementation supports the definition
     */
    public final Agent<?> create(PromptAgentDefinition definition) {
        PromptAgentDefinition checked = Objects.requireNonNull(definition, "definition");
        return tryCreate(checked)
                .orElseThrow(() -> new DeclarativeAgentValidationException(
                        "No prompt-agent factory supports agent '" + checked.name() + "'."));
    }

    /**
     * Tries to create an agent.
     *
     * @param definition immutable prompt-agent definition
     * @return created agent, or empty when this factory does not support the definition
     */
    public abstract Optional<Agent<?>> tryCreate(PromptAgentDefinition definition);
}
