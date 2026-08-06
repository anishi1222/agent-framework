// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one immutable context-provider contribution.
 *
 * @param instructions ordered additional instructions
 * @param messages ordered additional messages
 * @param metadata immutable run metadata additions
 * @param tools immutable additional tool declarations
 */
public record ContextContribution(
        List<String> instructions, List<Message> messages, Map<String, StateValue> metadata, List<Tool> tools) {
    private static final ContextContribution EMPTY = new ContextContribution(List.of(), List.of(), Map.of(), List.of());

    /** Creates and defensively copies a context contribution. */
    public ContextContribution {
        AgentValidation.requireNonNull(instructions, "instructions");
        ArrayList<String> copiedInstructions = new ArrayList<>(instructions.size());
        instructions.forEach(
                instruction -> copiedInstructions.add(AgentValidation.requireNonBlank(instruction, "instruction")));
        instructions = List.copyOf(copiedInstructions);
        messages = AgentValidation.copyMessages(messages);
        metadata = AgentValidation.copyMetadata(metadata);
        AgentValidation.requireNonNull(tools, "tools");
        ArrayList<Tool> copiedTools = new ArrayList<>(tools.size());
        tools.forEach(tool -> copiedTools.add(AgentValidation.requireNonNull(tool, "tool")));
        tools = List.copyOf(copiedTools);
    }

    /**
     * Returns an empty contribution.
     *
     * @return shared empty contribution
     */
    public static ContextContribution empty() {
        return EMPTY;
    }

    ContextContribution append(ContextContribution other) {
        AgentValidation.requireNonNull(other, "other");
        ArrayList<String> mergedInstructions = new ArrayList<>(instructions);
        mergedInstructions.addAll(other.instructions);
        ArrayList<Message> mergedMessages = new ArrayList<>(messages);
        mergedMessages.addAll(other.messages);
        LinkedHashMap<String, StateValue> mergedMetadata = new LinkedHashMap<>(metadata);
        mergedMetadata.putAll(other.metadata);
        ArrayList<Tool> mergedTools = new ArrayList<>(tools);
        mergedTools.addAll(other.tools);
        return new ContextContribution(mergedInstructions, mergedMessages, mergedMetadata, mergedTools);
    }
}
