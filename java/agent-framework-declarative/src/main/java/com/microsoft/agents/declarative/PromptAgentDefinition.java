// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines one immutable prompt agent independently of provider SDK types.
 *
 * <p>The stable agent identifier is {@link #name()}. {@link #displayName()} controls display
 * metadata only. Tool and context-provider entries are logical references resolved by caller-owned
 * registries during construction.
 *
 * @param kind supported kind, {@code Prompt} or the compatibility alias {@code Agent}
 * @param name stable agent identifier
 * @param displayName optional display name
 * @param description optional agent description
 * @param metadata immutable JSON-shaped request metadata
 * @param model model and chat-client selection
 * @param tools ordered tool registry references
 * @param contextProviders ordered context-provider registry references
 * @param instructions optional primary instructions
 * @param additionalInstructions optional instructions appended after a blank line
 */
public record PromptAgentDefinition(
        String kind,
        String name,
        String displayName,
        String description,
        Map<String, StateValue> metadata,
        PromptModelDefinition model,
        List<String> tools,
        List<String> contextProviders,
        String instructions,
        String additionalInstructions) {
    /** Creates a validated immutable prompt-agent definition. */
    public PromptAgentDefinition {
        kind = AgentDefinitionValidation.requireNonBlank(kind, "kind");
        if (!kind.equals("Prompt") && !kind.equals("Agent")) {
            throw new DeclarativeAgentValidationException(
                    "Unsupported agent kind '" + kind + "'; expected 'Prompt' or 'Agent'.");
        }
        name = AgentDefinitionValidation.requireNonBlank(name, "name");
        displayName = AgentDefinitionValidation.optionalNonBlank(displayName, "displayName");
        description = AgentDefinitionValidation.optionalNonBlank(description, "description");
        metadata = copyMetadata(metadata);
        model = Objects.requireNonNull(model, "model");
        tools = copyDistinctReferences(tools, "tools");
        contextProviders = copyDistinctReferences(contextProviders, "contextProviders");
        instructions = AgentDefinitionValidation.optionalNonBlank(instructions, "instructions");
        additionalInstructions =
                AgentDefinitionValidation.optionalNonBlank(additionalInstructions, "additionalInstructions");
    }

    /**
     * Returns the deterministic instruction text supplied to {@code ChatAgent}.
     *
     * @return combined instructions, or {@code null} when neither instruction field is present
     */
    public String combinedInstructions() {
        if (instructions == null) {
            return additionalInstructions;
        }
        if (additionalInstructions == null) {
            return instructions;
        }
        return instructions + "\n\n" + additionalInstructions;
    }

    private static Map<String, StateValue> copyMetadata(Map<String, StateValue> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                AgentDefinitionValidation.requireNonBlank(key, "metadata key"),
                Objects.requireNonNull(value, "metadata value")));
        return Collections.unmodifiableMap(copy);
    }

    private static List<String> copyDistinctReferences(List<String> values, String name) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String reference = AgentDefinitionValidation.requireNonBlank(value, name + " reference");
            if (!seen.add(reference)) {
                throw new DeclarativeAgentValidationException("Duplicate " + name + " reference '" + reference + "'.");
            }
            copy.add(reference);
        }
        return List.copyOf(copy);
    }
}
