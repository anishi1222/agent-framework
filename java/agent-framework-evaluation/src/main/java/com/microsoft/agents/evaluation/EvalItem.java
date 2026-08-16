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
import java.util.stream.Collectors;

/**
 * Represents one immutable provider-neutral evaluation item.
 *
 * <p>The conversation is the source of truth. Query and response text are derived from the selected
 * {@link ConversationSplitter}.
 */
public final class EvalItem {
    private final List<Message> conversation;
    private final List<EvaluationTool> tools;
    private final String context;
    private final String expectedOutput;
    private final List<ExpectedToolCall> expectedToolCalls;
    private final ConversationSplitter splitter;
    private final Map<String, StateValue> metadata;

    private EvalItem(Builder builder) {
        conversation = EvaluationValidation.copyList(builder.conversation, "conversation");
        if (conversation.isEmpty()) {
            throw new IllegalArgumentException("conversation must contain at least one message.");
        }
        tools = EvaluationValidation.copyList(builder.tools, "tools");
        context = EvaluationValidation.optionalNonBlank(builder.context, "context");
        expectedOutput = builder.expectedOutput;
        expectedToolCalls = EvaluationValidation.copyList(builder.expectedToolCalls, "expectedToolCalls");
        splitter = Objects.requireNonNull(builder.splitter, "splitter");
        metadata = EvaluationValidation.copyMap(builder.metadata, "metadata");
        split();
    }

    /**
     * Creates a builder for a full conversation.
     *
     * @param conversation ordered conversation
     * @return evaluation-item builder
     */
    public static Builder builder(List<Message> conversation) {
        return new Builder(conversation);
    }

    /**
     * Creates a text-only item with one user message and one assistant message.
     *
     * @param query non-blank user query
     * @param response assistant response, which may be empty
     * @return immutable evaluation item
     */
    public static EvalItem of(String query, String response) {
        String checkedQuery = EvaluationValidation.requireNonBlank(query, "query");
        Objects.requireNonNull(response, "response");
        return builder(List.of(Message.text(Role.USER, checkedQuery), Message.text(Role.ASSISTANT, response)))
                .build();
    }

    /**
     * Returns the full immutable conversation.
     *
     * @return ordered conversation
     */
    public List<Message> conversation() {
        return conversation;
    }

    /**
     * Returns immutable available-tool descriptions.
     *
     * @return available tools
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
     * Returns immutable expected tool calls.
     *
     * @return expected tool calls
     */
    public List<ExpectedToolCall> expectedToolCalls() {
        return expectedToolCalls;
    }

    /**
     * Returns the configured conversation splitter.
     *
     * @return conversation splitter
     */
    public ConversationSplitter splitter() {
        return splitter;
    }

    /**
     * Returns immutable item metadata.
     *
     * @return framework-owned JSON-shaped metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /**
     * Splits the conversation with the configured splitter.
     *
     * @return validated immutable split
     */
    public ConversationSplit split() {
        return split(splitter);
    }

    /**
     * Splits the conversation with an explicit splitter.
     *
     * @param explicitSplitter splitter to apply
     * @return validated immutable split
     */
    public ConversationSplit split(ConversationSplitter explicitSplitter) {
        ConversationSplit result =
                Objects.requireNonNull(explicitSplitter, "explicitSplitter").split(conversation);
        if (result == null) {
            throw new IllegalArgumentException("The conversation splitter returned null.");
        }
        ArrayList<Message> recombined = new ArrayList<>(result.queryMessages());
        recombined.addAll(result.responseMessages());
        if (!conversation.equals(recombined)) {
            throw new IllegalArgumentException(
                    "The conversation splitter must preserve every message exactly once and in order.");
        }
        return result;
    }

    /**
     * Returns the last user-message text on the query side of the split.
     *
     * @return query text, or an empty string when no user message is present
     */
    public String query() {
        List<Message> queryMessages = split().queryMessages();
        for (int index = queryMessages.size() - 1; index >= 0; index--) {
            Message message = queryMessages.get(index);
            if (Role.USER.equals(message.role())) {
                return message.text();
            }
        }
        return "";
    }

    /**
     * Returns assistant text on the response side joined with one space.
     *
     * @return response text, or an empty string when no assistant text is present
     */
    public String response() {
        return split().responseMessages().stream()
                .filter(message -> Role.ASSISTANT.equals(message.role()))
                .map(Message::text)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining(" "));
    }

    /**
     * Creates one cumulative evaluation item per user turn.
     *
     * @param conversation full ordered conversation
     * @return immutable items in user-turn order
     */
    public static List<EvalItem> perTurnItems(List<Message> conversation) {
        return perTurnItems(conversation, List.of(), null, Map.of());
    }

    /**
     * Creates one cumulative evaluation item per user turn with shared evaluation context.
     *
     * @param conversation full ordered conversation
     * @param tools available tools
     * @param context optional grounding context
     * @param metadata shared metadata
     * @return immutable items in user-turn order
     */
    public static List<EvalItem> perTurnItems(
            List<Message> conversation, List<EvaluationTool> tools, String context, Map<String, StateValue> metadata) {
        List<Message> checkedConversation = EvaluationValidation.copyList(conversation, "conversation");
        List<Integer> userIndexes = new ArrayList<>();
        for (int index = 0; index < checkedConversation.size(); index++) {
            if (Role.USER.equals(checkedConversation.get(index).role())) {
                userIndexes.add(index);
            }
        }
        List<EvalItem> items = new ArrayList<>();
        for (int turn = 0; turn < userIndexes.size(); turn++) {
            int end = turn + 1 < userIndexes.size() ? userIndexes.get(turn + 1) : checkedConversation.size();
            items.add(builder(checkedConversation.subList(0, end))
                    .tools(tools)
                    .context(context)
                    .metadata(metadata)
                    .build());
        }
        return List.copyOf(items);
    }

    /** Builds an immutable {@link EvalItem}. */
    public static final class Builder {
        private final List<Message> conversation;
        private List<EvaluationTool> tools = List.of();
        private String context;
        private String expectedOutput;
        private List<ExpectedToolCall> expectedToolCalls = List.of();
        private ConversationSplitter splitter = ConversationSplitters.lastTurn();
        private Map<String, StateValue> metadata = Map.of();

        private Builder(List<Message> conversation) {
            this.conversation = EvaluationValidation.copyList(conversation, "conversation");
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
         * Sets immutable framework-owned metadata.
         *
         * @param metadata JSON-shaped metadata
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = new LinkedHashMap<>(EvaluationValidation.copyMap(metadata, "metadata"));
            return this;
        }

        /**
         * Creates the immutable evaluation item.
         *
         * @return evaluation item
         */
        public EvalItem build() {
            return new EvalItem(this);
        }
    }
}
