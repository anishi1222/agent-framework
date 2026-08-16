// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.evaluation;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one immutable agent or workflow evaluation case before execution.
 */
public final class EvaluationCase {
    private final List<Message> inputMessages;
    private final List<EvaluationTool> tools;
    private final String context;
    private final String expectedOutput;
    private final List<ExpectedToolCall> expectedToolCalls;
    private final ConversationSplitter splitter;
    private final Map<String, StateValue> metadata;

    private EvaluationCase(Builder builder) {
        inputMessages = EvaluationValidation.copyList(builder.inputMessages, "inputMessages");
        if (inputMessages.isEmpty()) {
            throw new IllegalArgumentException("inputMessages must contain at least one message.");
        }
        tools = EvaluationValidation.copyList(builder.tools, "tools");
        context = EvaluationValidation.optionalNonBlank(builder.context, "context");
        expectedOutput = builder.expectedOutput;
        expectedToolCalls = EvaluationValidation.copyList(builder.expectedToolCalls, "expectedToolCalls");
        splitter = Objects.requireNonNull(builder.splitter, "splitter");
        metadata = EvaluationValidation.copyMap(builder.metadata, "metadata");
    }

    /**
     * Creates a builder from ordered input messages.
     *
     * @param inputMessages ordered input messages
     * @return evaluation-case builder
     */
    public static Builder builder(List<Message> inputMessages) {
        return new Builder(inputMessages);
    }

    /**
     * Creates a text-only user-query case.
     *
     * @param query non-blank query
     * @return immutable evaluation case
     */
    public static EvaluationCase text(String query) {
        return builder(List.of(Message.text(Role.USER, EvaluationValidation.requireNonBlank(query, "query"))))
                .build();
    }

    /**
     * Returns ordered input messages.
     *
     * @return immutable input messages
     */
    public List<Message> inputMessages() {
        return inputMessages;
    }

    /**
     * Returns available tool descriptions.
     *
     * @return immutable tools
     */
    public List<EvaluationTool> tools() {
        return tools;
    }

    /**
     * Returns optional grounding context.
     *
     * @return context, or {@code null}
     */
    public String context() {
        return context;
    }

    /**
     * Returns optional expected output.
     *
     * @return expected output, or {@code null}
     */
    public String expectedOutput() {
        return expectedOutput;
    }

    /**
     * Returns expected tool calls.
     *
     * @return immutable expected calls
     */
    public List<ExpectedToolCall> expectedToolCalls() {
        return expectedToolCalls;
    }

    /**
     * Returns the conversation splitter.
     *
     * @return splitter
     */
    public ConversationSplitter splitter() {
        return splitter;
    }

    /**
     * Returns framework-owned metadata.
     *
     * @return immutable metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    EvalItem toEvalItem(List<Message> responseMessages) {
        List<Message> conversation = new ArrayList<>(inputMessages);
        conversation.addAll(EvaluationValidation.copyList(responseMessages, "responseMessages"));
        return EvalItem.builder(conversation)
                .tools(tools)
                .context(context)
                .expectedOutput(expectedOutput)
                .expectedToolCalls(expectedToolCalls)
                .splitter(splitter)
                .metadata(metadata)
                .build();
    }

    /** Builds an immutable {@link EvaluationCase}. */
    public static final class Builder {
        private final List<Message> inputMessages;
        private List<EvaluationTool> tools = List.of();
        private String context;
        private String expectedOutput;
        private List<ExpectedToolCall> expectedToolCalls = List.of();
        private ConversationSplitter splitter = ConversationSplitters.lastTurn();
        private Map<String, StateValue> metadata = Map.of();

        private Builder(List<Message> inputMessages) {
            this.inputMessages = EvaluationValidation.copyList(inputMessages, "inputMessages");
        }

        /**
         * Sets available tool descriptions.
         *
         * @param tools available tools
         * @return this builder
         */
        public Builder tools(List<EvaluationTool> tools) {
            this.tools = EvaluationValidation.copyList(tools, "tools");
            return this;
        }

        /**
         * Sets optional grounding context.
         *
         * @param context context, or {@code null}
         * @return this builder
         */
        public Builder context(String context) {
            this.context = EvaluationValidation.optionalNonBlank(context, "context");
            return this;
        }

        /**
         * Sets optional expected output.
         *
         * @param expectedOutput expected output, or {@code null}
         * @return this builder
         */
        public Builder expectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
            return this;
        }

        /**
         * Sets expected tool calls.
         *
         * @param expectedToolCalls expected tool calls
         * @return this builder
         */
        public Builder expectedToolCalls(List<ExpectedToolCall> expectedToolCalls) {
            this.expectedToolCalls = EvaluationValidation.copyList(expectedToolCalls, "expectedToolCalls");
            return this;
        }

        /**
         * Sets the conversation splitter.
         *
         * @param splitter conversation splitter
         * @return this builder
         */
        public Builder splitter(ConversationSplitter splitter) {
            this.splitter = Objects.requireNonNull(splitter, "splitter");
            return this;
        }

        /**
         * Sets framework-owned metadata.
         *
         * @param metadata JSON-shaped metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = new LinkedHashMap<>(EvaluationValidation.copyMap(metadata, "metadata"));
            return this;
        }

        /**
         * Creates the immutable evaluation case.
         *
         * @return evaluation case
         */
        public EvaluationCase build() {
            return new EvaluationCase(this);
        }
    }
}
