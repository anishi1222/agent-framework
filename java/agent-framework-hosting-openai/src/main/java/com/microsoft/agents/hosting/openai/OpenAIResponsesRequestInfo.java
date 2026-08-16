// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the validated request settings that an application may explicitly map onto an agent run.
 *
 * @param model informational model identifier
 * @param temperature optional sampling temperature
 * @param topP optional nucleus-sampling value
 * @param maxOutputTokens optional output-token limit
 * @param instructions optional caller instructions
 * @param tools immutable function-tool declarations
 * @param toolChoice optional tool-selection value
 * @param parallelToolCalls optional parallel-tool setting
 * @param maxToolCalls optional total tool-call bound
 * @param metadata immutable string metadata
 * @param user optional end-user correlation identifier
 */
public record OpenAIResponsesRequestInfo(
        String model,
        Double temperature,
        Double topP,
        Integer maxOutputTokens,
        String instructions,
        List<StateValue.ObjectValue> tools,
        StateValue toolChoice,
        Boolean parallelToolCalls,
        Integer maxToolCalls,
        Map<String, String> metadata,
        String user) {
    /** Creates a validated immutable request-info value. */
    public OpenAIResponsesRequestInfo {
        model = optionalNonBlank(model, "model");
        instructions = optionalNonBlank(instructions, "instructions");
        user = optionalNonBlank(user, "user");
        tools = List.copyOf(java.util.Objects.requireNonNull(tools, "tools"));
        if (tools.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("tools must not contain null.");
        }
        java.util.Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        metadata.forEach((key, value) ->
                copied.put(requireNonBlank(key, "metadata key"), requireNonBlank(value, "metadata value")));
        metadata = Map.copyOf(copied);
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2.");
        }
        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("topP must be between 0 and 1.");
        }
        requirePositive(maxOutputTokens, "maxOutputTokens");
        requirePositive(maxToolCalls, "maxToolCalls");
    }

    private static String optionalNonBlank(String value, String name) {
        return value == null ? null : requireNonBlank(value, name);
    }

    private static String requireNonBlank(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static void requirePositive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
    }
}
