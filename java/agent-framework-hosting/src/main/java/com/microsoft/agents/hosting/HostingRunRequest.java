// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunOptions;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the protocol-neutral input accepted by hosted agents and workflows.
 *
 * @param messages ordered agent messages
 * @param input optional workflow input represented as safe JSON-shaped state
 * @param options immutable agent run options
 * @param metadata immutable request metadata
 */
public record HostingRunRequest(
        List<Message> messages, StateValue input, RunOptions options, Map<String, StateValue> metadata) {
    /** Creates a validated immutable request. */
    public HostingRunRequest {
        messages = HostingValidation.copyList(messages, "messages");
        options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, StateValue> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> copy.put(
                HostingValidation.nonBlank(key, "metadata key"), Objects.requireNonNull(value, "metadata value")));
        metadata = Map.copyOf(copy);
        if (messages.isEmpty() && input == null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "A hosted request must contain messages or workflow input.");
        }
    }

    /**
     * Creates an agent request.
     *
     * @param messages ordered messages
     * @param options run options
     * @return request
     */
    public static HostingRunRequest forAgent(List<Message> messages, RunOptions options) {
        return new HostingRunRequest(messages, null, options, Map.of());
    }

    /**
     * Creates a workflow request.
     *
     * @param input JSON-shaped workflow input
     * @return request
     */
    public static HostingRunRequest forWorkflow(StateValue input) {
        return new HostingRunRequest(List.of(), Objects.requireNonNull(input, "input"), RunOptions.empty(), Map.of());
    }
}
