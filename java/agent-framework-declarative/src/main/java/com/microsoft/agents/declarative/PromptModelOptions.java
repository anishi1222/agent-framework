// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines provider-neutral model options supported by {@code ChatOptions}.
 *
 * @param frequencyPenalty optional value from -2 through 2
 * @param maxOutputTokens optional positive output-token limit
 * @param presencePenalty optional value from -2 through 2
 * @param seed optional deterministic seed
 * @param temperature optional value from 0 through 2
 * @param topP optional value from 0 through 1
 * @param stopSequences ordered non-blank stop sequences
 * @param allowMultipleToolCalls optional multiple-tool-call preference
 */
public record PromptModelOptions(
        Double frequencyPenalty,
        Integer maxOutputTokens,
        Double presencePenalty,
        Long seed,
        Double temperature,
        Double topP,
        List<String> stopSequences,
        Boolean allowMultipleToolCalls) {
    /** Creates validated immutable model options. */
    public PromptModelOptions {
        requireRange(frequencyPenalty, -2.0, 2.0, "frequencyPenalty");
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new DeclarativeAgentValidationException("model.options.maxOutputTokens must be greater than zero.");
        }
        requireRange(presencePenalty, -2.0, 2.0, "presencePenalty");
        requireRange(temperature, 0.0, 2.0, "temperature");
        requireRange(topP, 0.0, 1.0, "topP");
        stopSequences = copyStrings(stopSequences, "model.options.stopSequences");
    }

    /**
     * Returns options with no explicit values.
     *
     * @return empty model options
     */
    public static PromptModelOptions empty() {
        return new PromptModelOptions(null, null, null, null, null, null, List.of(), null);
    }

    private static void requireRange(Double value, double minimum, double maximum, String name) {
        if (value != null && (!Double.isFinite(value) || value < minimum || value > maximum)) {
            throw new DeclarativeAgentValidationException(
                    "model.options." + name + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static List<String> copyStrings(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(AgentDefinitionValidation.requireNonBlank(value, name + " element"));
        }
        return List.copyOf(copy);
    }
}
