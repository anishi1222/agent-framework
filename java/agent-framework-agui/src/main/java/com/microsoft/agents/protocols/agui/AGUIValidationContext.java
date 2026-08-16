// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.agui;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Supplies prior-thread entities required to validate a resumed event stream.
 *
 * @param messageIds known message identifiers
 * @param toolCallIds known completed tool-call identifiers
 * @param state optional synchronized state baseline
 */
public record AGUIValidationContext(Set<String> messageIds, Set<String> toolCallIds, StateValue state) {
    /** Creates a validated immutable context. */
    public AGUIValidationContext {
        messageIds = copyIds(messageIds, "messageIds");
        toolCallIds = copyIds(toolCallIds, "toolCallIds");
    }

    /**
     * Returns an empty validation context.
     *
     * @return empty context
     */
    public static AGUIValidationContext empty() {
        return new AGUIValidationContext(Set.of(), Set.of(), null);
    }

    /**
     * Derives known identifiers and state from a run input.
     *
     * @param input run input
     * @return validation context
     */
    public static AGUIValidationContext fromInput(RunAgentInput input) {
        java.util.Objects.requireNonNull(input, "input");
        LinkedHashSet<String> messages = new LinkedHashSet<>();
        LinkedHashSet<String> toolCalls = new LinkedHashSet<>();
        for (AGUIMessage message : input.messages()) {
            messages.add(message.id());
            if (message instanceof AGUIMessages.Assistant assistant) {
                assistant.toolCalls().forEach(call -> toolCalls.add(call.id()));
            } else if (message instanceof AGUIMessages.Tool tool) {
                toolCalls.add(tool.toolCallId());
            }
        }
        return new AGUIValidationContext(messages, toolCalls, input.state());
    }

    private static Set<String> copyIds(Set<String> values, String name) {
        java.util.Objects.requireNonNull(values, name);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String checked = AGUIValidation.nonBlank(value, name + " element");
            if (!copy.add(checked)) {
                throw AGUIValidation.invalid(name + " contains a duplicate.");
            }
        }
        return Set.copyOf(copy);
    }
}
