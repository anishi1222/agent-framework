// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Defines immutable provider-neutral chat generation options.
 *
 * @param model optional model identifier
 * @param temperature optional value from 0 through 2
 * @param topP optional value from 0 through 1
 * @param maxTokens optional positive output-token limit
 * @param stop ordered non-blank stop sequences
 * @param seed optional deterministic seed
 * @param frequencyPenalty optional value from -2 through 2
 * @param presencePenalty optional value from -2 through 2
 * @param toolChoice optional provider-neutral tool selection
 * @param allowMultipleToolCalls optional multiple-tool-call preference
 * @param user optional end-user identifier
 * @param store optional provider persistence preference
 * @param conversationId optional provider conversation identifier
 * @param instructions optional request instructions
 * @param metadata immutable request metadata
 */
public record ChatOptions(
        String model,
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        Long seed,
        Double frequencyPenalty,
        Double presencePenalty,
        ToolChoice toolChoice,
        Boolean allowMultipleToolCalls,
        String user,
        Boolean store,
        String conversationId,
        String instructions,
        Map<String, StateValue> metadata) {
    /** Creates validated chat options and defensively copies collections. */
    public ChatOptions {
        model = CoreValidation.optionalNonBlank(model, "model");
        requireRange(temperature, 0.0, 2.0, "temperature");
        requireRange(topP, 0.0, 1.0, "topP");
        if (maxTokens != null && maxTokens <= 0) {
            throw new ValidationException("maxTokens must be greater than zero when present.");
        }
        stop = copyStop(stop);
        requireRange(frequencyPenalty, -2.0, 2.0, "frequencyPenalty");
        requireRange(presencePenalty, -2.0, 2.0, "presencePenalty");
        user = CoreValidation.optionalNonBlank(user, "user");
        conversationId = CoreValidation.optionalNonBlank(conversationId, "conversationId");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Returns empty options.
     *
     * @return options with no explicit provider settings
     */
    public static ChatOptions empty() {
        return builder().build();
    }

    /**
     * Creates a builder.
     *
     * @return chat-options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requireRange(Double value, double minimum, double maximum, String name) {
        if (value != null && (!Double.isFinite(value) || value < minimum || value > maximum)) {
            throw new ValidationException(name + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static List<String> copyStop(List<String> values) {
        Objects.requireNonNull(values, "stop");
        ArrayList<String> copy = new ArrayList<>(values.size());
        values.forEach(value -> copy.add(CoreValidation.requireNonBlank(value, "stop element")));
        return List.copyOf(copy);
    }

    /** Builds immutable {@link ChatOptions}. */
    public static final class Builder {
        private String model;

        private Double temperature;

        private Double topP;

        private Integer maxTokens;

        private List<String> stop = List.of();

        private Long seed;

        private Double frequencyPenalty;

        private Double presencePenalty;

        private ToolChoice toolChoice;

        private Boolean allowMultipleToolCalls;

        private String user;

        private Boolean store;

        private String conversationId;

        private String instructions;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /**
         * Sets the model identifier.
         *
         * @param model model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the temperature.
         *
         * @param temperature value from 0 through 2
         * @return this builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Sets nucleus sampling.
         *
         * @param topP value from 0 through 1
         * @return this builder
         */
        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Sets the output-token limit.
         *
         * @param maxTokens positive limit
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets ordered stop sequences.
         *
         * @param stop non-blank stop sequences
         * @return this builder
         */
        public Builder stop(List<String> stop) {
            this.stop = Objects.requireNonNull(stop, "stop");
            return this;
        }

        /**
         * Sets the deterministic seed.
         *
         * @param seed seed
         * @return this builder
         */
        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Sets the frequency penalty.
         *
         * @param frequencyPenalty value from -2 through 2
         * @return this builder
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Sets the presence penalty.
         *
         * @param presencePenalty value from -2 through 2
         * @return this builder
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Sets tool-selection behavior.
         *
         * @param toolChoice tool choice
         * @return this builder
         */
        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = Objects.requireNonNull(toolChoice, "toolChoice");
            return this;
        }

        /**
         * Sets whether multiple tool calls are allowed.
         *
         * @param allowMultipleToolCalls preference
         * @return this builder
         */
        public Builder allowMultipleToolCalls(boolean allowMultipleToolCalls) {
            this.allowMultipleToolCalls = allowMultipleToolCalls;
            return this;
        }

        /**
         * Sets an end-user identifier.
         *
         * @param user end-user identifier
         * @return this builder
         */
        public Builder user(String user) {
            this.user = user;
            return this;
        }

        /**
         * Sets the provider persistence preference.
         *
         * @param store persistence preference
         * @return this builder
         */
        public Builder store(boolean store) {
            this.store = store;
            return this;
        }

        /**
         * Sets the provider conversation identifier.
         *
         * @param conversationId conversation identifier
         * @return this builder
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        /**
         * Sets request instructions.
         *
         * @param instructions instructions
         * @return this builder
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * Sets request metadata.
         *
         * @param metadata JSON-shaped metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates immutable chat options.
         *
         * @return chat options
         */
        public ChatOptions build() {
            return new ChatOptions(
                    model,
                    temperature,
                    topP,
                    maxTokens,
                    stop,
                    seed,
                    frequencyPenalty,
                    presencePenalty,
                    toolChoice,
                    allowMultipleToolCalls,
                    user,
                    store,
                    conversationId,
                    instructions,
                    metadata);
        }
    }
}
