// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates ordered streaming updates into immutable responses.
 *
 * <p>An aggregation is open until an update supplies a finish reason or {@code finish()} is
 * called. A terminal aggregation is immutable: later updates and value changes fail explicitly.
 * Each update is applied transactionally, so an incompatible correlation identifier or content
 * delta leaves the prior state unchanged.
 */
public final class ResponseAggregator {
    private ResponseAggregator() {}

    /**
     * Creates an open chat-response aggregation.
     *
     * @return chat aggregation
     */
    public static ChatAggregation chat() {
        return new ChatAggregation();
    }

    /**
     * Creates an open agent-response aggregation.
     *
     * @param <T> structured response value type
     * @return agent aggregation
     */
    public static <T> AgentAggregation<T> agent() {
        return new AgentAggregation<>();
    }

    /**
     * Aggregates all chat updates in iteration order.
     *
     * @param updates ordered updates
     * @return terminal chat response
     */
    public static ChatResponse aggregateChat(Iterable<ChatResponseUpdate> updates) {
        Objects.requireNonNull(updates, "updates");
        ChatAggregation aggregation = chat();
        updates.forEach(aggregation::add);
        return aggregation.isTerminal() ? aggregation.response() : aggregation.finish();
    }

    /**
     * Aggregates all agent updates in iteration order.
     *
     * @param updates ordered updates
     * @param <T> structured response value type
     * @return terminal agent response
     */
    public static <T> AgentResponse<T> aggregateAgent(Iterable<AgentResponseUpdate> updates) {
        Objects.requireNonNull(updates, "updates");
        AgentAggregation<T> aggregation = agent();
        updates.forEach(aggregation::add);
        return aggregation.isTerminal() ? aggregation.response() : aggregation.finish();
    }

    /**
     * Owns the lifecycle of one chat-response aggregation.
     */
    public static final class ChatAggregation {
        private final Accumulator accumulator = new Accumulator();

        private ChatAggregation() {}

        /**
         * Applies one update atomically.
         *
         * @param update next ordered update
         * @return this aggregation
         * @throws IllegalStateException when the aggregation is terminal
         * @throws ValidationException when the update is incompatible with prior state
         */
        public ChatAggregation add(ChatResponseUpdate update) {
            Objects.requireNonNull(update, "update");
            accumulator.add(UpdateView.from(update));
            return this;
        }

        /**
         * Terminates an open aggregation and returns its response.
         *
         * <p>Calling this method again is idempotent.
         *
         * @return terminal response
         */
        public ChatResponse finish() {
            accumulator.terminal = true;
            return accumulator.chatResponse();
        }

        /**
         * Returns the response after terminal completion.
         *
         * @return terminal response
         * @throws IllegalStateException when the aggregation is still open
         */
        public ChatResponse response() {
            requireTerminal(accumulator);
            return accumulator.chatResponse();
        }

        /**
         * Reports whether the aggregation rejects further updates.
         *
         * @return {@code true} after terminal completion
         */
        public boolean isTerminal() {
            return accumulator.terminal;
        }

        long inspectedExistingContentItemsForTesting() {
            return accumulator.inspectedExistingContentItems;
        }
    }

    /**
     * Owns the lifecycle of one agent-response aggregation.
     *
     * @param <T> structured response value type
     */
    public static final class AgentAggregation<T> {
        private final Accumulator accumulator = new Accumulator();

        private T value;

        private AgentAggregation() {}

        /**
         * Applies one update atomically.
         *
         * @param update next ordered update
         * @return this aggregation
         * @throws IllegalStateException when the aggregation is terminal
         * @throws ValidationException when the update is incompatible with prior state
         */
        public AgentAggregation<T> add(AgentResponseUpdate update) {
            Objects.requireNonNull(update, "update");
            accumulator.add(UpdateView.from(update));
            return this;
        }

        /**
         * Sets the optional structured value before terminal completion.
         *
         * @param value structured value
         * @return this aggregation
         * @throws IllegalStateException when the aggregation is terminal
         */
        public AgentAggregation<T> value(T value) {
            if (accumulator.terminal) {
                throw new IllegalStateException("The response aggregation is terminal.");
            }
            this.value = value;
            return this;
        }

        /**
         * Terminates an open aggregation and returns its response.
         *
         * <p>Calling this method again is idempotent.
         *
         * @return terminal response
         */
        public AgentResponse<T> finish() {
            accumulator.terminal = true;
            return accumulator.agentResponse(value);
        }

        /**
         * Returns the response after terminal completion.
         *
         * @return terminal response
         * @throws IllegalStateException when the aggregation is still open
         */
        public AgentResponse<T> response() {
            requireTerminal(accumulator);
            return accumulator.agentResponse(value);
        }

        /**
         * Reports whether the aggregation rejects further updates.
         *
         * @return {@code true} after terminal completion
         */
        public boolean isTerminal() {
            return accumulator.terminal;
        }

        long inspectedExistingContentItemsForTesting() {
            return accumulator.inspectedExistingContentItems;
        }
    }

    private static void requireTerminal(Accumulator accumulator) {
        if (!accumulator.terminal) {
            throw new IllegalStateException("The response aggregation is still open.");
        }
    }

    private record UpdateView(
            Long sequence,
            List<Content> contents,
            Role role,
            String authorName,
            String agentId,
            String responseId,
            String messageId,
            String conversationId,
            String model,
            Instant createdAt,
            FinishReason finishReason,
            UsageDetails usage,
            StateValue continuationToken,
            Map<String, StateValue> metadata) {
        static UpdateView from(ChatResponseUpdate update) {
            return new UpdateView(
                    update.sequence(),
                    update.contents(),
                    update.role(),
                    update.authorName(),
                    null,
                    update.responseId(),
                    update.messageId(),
                    update.conversationId(),
                    update.model(),
                    update.createdAt(),
                    update.finishReason(),
                    update.usage(),
                    update.continuationToken(),
                    update.metadata());
        }

        static UpdateView from(AgentResponseUpdate update) {
            return new UpdateView(
                    update.sequence(),
                    update.contents(),
                    update.role(),
                    update.authorName(),
                    update.agentId(),
                    update.responseId(),
                    update.messageId(),
                    null,
                    null,
                    update.createdAt(),
                    update.finishReason(),
                    update.usage(),
                    update.continuationToken(),
                    update.metadata());
        }
    }

    private static final class Accumulator {
        private final List<MessageState> messages = new ArrayList<>();

        private final LinkedHashMap<String, StateValue> metadata = new LinkedHashMap<>();

        private final List<Long> updateSequences = new ArrayList<>();

        private String responseId;

        private String agentId;

        private String conversationId;

        private String model;

        private Instant createdAt;

        private FinishReason finishReason;

        private UsageDetails usage;

        private StateValue continuationToken;

        private boolean terminal;

        private long inspectedExistingContentItems;

        private void add(UpdateView update) {
            if (terminal) {
                throw new IllegalStateException("The response aggregation is terminal.");
            }

            String nextResponseId = stableIdentifier(responseId, update.responseId(), "responseId");
            String nextAgentId = stableIdentifier(agentId, update.agentId(), "agentId");
            String nextConversationId = stableIdentifier(conversationId, update.conversationId(), "conversationId");

            ArrayList<Content> messageContents = new ArrayList<>();
            UsageDetails nextUsage = usage;
            for (Content content : update.contents()) {
                if (content instanceof UsageContent usageContent) {
                    nextUsage = foldUsage(nextUsage, usageContent.usage());
                } else {
                    messageContents.add(content);
                }
            }
            if (update.usage() != null) {
                nextUsage = foldUsage(nextUsage, update.usage());
            }

            MessageDelta messageDelta = deriveMessageDelta(update, messageContents);
            UpdateDelta delta = new UpdateDelta(
                    nextResponseId,
                    nextAgentId,
                    nextConversationId,
                    messageDelta,
                    update.model() != null ? update.model() : model,
                    update.createdAt() != null ? update.createdAt() : createdAt,
                    update.finishReason() != null ? update.finishReason() : finishReason,
                    nextUsage,
                    update.continuationToken() != null ? update.continuationToken() : continuationToken,
                    update.metadata(),
                    update.sequence(),
                    update.finishReason() != null);
            apply(delta);
        }

        private MessageDelta deriveMessageDelta(UpdateView update, List<Content> incomingContents) {
            MessageState current = messages.isEmpty() ? null : messages.getLast();
            boolean messageChanged = current != null
                    && update.messageId() != null
                    && current.messageId != null
                    && !current.messageId.equals(update.messageId());
            boolean roleChanged = current != null && update.role() != null && !current.role.equals(update.role());
            boolean createMessage = current == null || messageChanged || roleChanged;
            MessageState target = createMessage ? null : current;
            Role nextRole = update.role() != null ? update.role() : target == null ? Role.ASSISTANT : target.role;
            String nextAuthorName =
                    update.authorName() != null ? update.authorName() : target == null ? null : target.authorName;
            String nextMessageId =
                    update.messageId() != null ? update.messageId() : target == null ? null : target.messageId;
            ContentDelta contentDelta =
                    ContentDelta.derive(target == null ? List.of() : target.contents, incomingContents);
            return new MessageDelta(createMessage, target, nextRole, nextAuthorName, nextMessageId, contentDelta);
        }

        private void apply(UpdateDelta delta) {
            responseId = delta.responseId();
            agentId = delta.agentId();
            conversationId = delta.conversationId();
            delta.message().apply(messages);
            inspectedExistingContentItems += delta.message().contentDelta().inspectedExistingItems();
            model = delta.model();
            createdAt = delta.createdAt();
            finishReason = delta.finishReason();
            usage = delta.usage();
            continuationToken = delta.continuationToken();
            metadata.putAll(delta.metadata());
            if (delta.sequence() != null) {
                updateSequences.add(delta.sequence());
            }
            terminal = delta.terminal();
        }

        private static UsageDetails foldUsage(UsageDetails current, UsageDetails update) {
            return current == null ? UsageDetails.empty().fold(update) : current.fold(update);
        }

        private ChatResponse chatResponse() {
            return new ChatResponse(
                    builtMessages(),
                    responseId,
                    conversationId,
                    model,
                    createdAt,
                    finishReason,
                    usage,
                    continuationToken,
                    metadata,
                    updateSequences);
        }

        private <T> AgentResponse<T> agentResponse(T value) {
            return new AgentResponse<>(
                    builtMessages(),
                    responseId,
                    agentId,
                    createdAt,
                    finishReason,
                    usage,
                    value,
                    continuationToken,
                    metadata,
                    updateSequences);
        }

        private List<Message> builtMessages() {
            return messages.stream().map(MessageState::build).toList();
        }
    }

    private record UpdateDelta(
            String responseId,
            String agentId,
            String conversationId,
            MessageDelta message,
            String model,
            Instant createdAt,
            FinishReason finishReason,
            UsageDetails usage,
            StateValue continuationToken,
            Map<String, StateValue> metadata,
            Long sequence,
            boolean terminal) {}

    private record MessageDelta(
            boolean createMessage,
            MessageState target,
            Role role,
            String authorName,
            String messageId,
            ContentDelta contentDelta) {
        private void apply(List<MessageState> messages) {
            MessageState message = target;
            if (createMessage) {
                message = new MessageState();
                messages.add(message);
            }
            message.role = role;
            message.authorName = authorName;
            message.messageId = messageId;
            contentDelta.apply(message.contents);
        }
    }

    private record ContentDelta(
            boolean replaceExistingLast, Content replacement, List<Content> appended, int inspectedExistingItems) {
        private static ContentDelta derive(List<Content> existing, List<Content> incoming) {
            boolean replaceExistingLast = false;
            Content replacement = null;
            ArrayList<Content> appended = new ArrayList<>();
            Content previous = existing.isEmpty() ? null : existing.getLast();
            boolean previousIsExistingLast = previous != null;
            int inspectedExistingItems = previous != null && !incoming.isEmpty() ? 1 : 0;

            for (Content content : incoming) {
                Objects.requireNonNull(content, "incoming content");
                if (previous == null) {
                    appended.add(content);
                    previous = content;
                    previousIsExistingLast = false;
                    continue;
                }
                Content merged = merge(previous, content);
                if (merged == null) {
                    appended.add(content);
                    previous = content;
                    previousIsExistingLast = false;
                } else if (previousIsExistingLast) {
                    replaceExistingLast = true;
                    replacement = merged;
                    previous = merged;
                } else {
                    appended.set(appended.size() - 1, merged);
                    previous = merged;
                }
            }
            return new ContentDelta(replaceExistingLast, replacement, List.copyOf(appended), inspectedExistingItems);
        }

        private void apply(List<Content> contents) {
            if (replaceExistingLast) {
                contents.set(contents.size() - 1, replacement);
            }
            contents.addAll(appended);
        }
    }

    private static String stableIdentifier(String current, String incoming, String name) {
        if (incoming == null) {
            return current;
        }
        if (current != null && !current.equals(incoming)) {
            throw new ValidationException("Incompatible " + name + " values '" + current + "' and '" + incoming + "'.");
        }
        return incoming;
    }

    private static final class MessageState {
        private Role role = Role.ASSISTANT;

        private final List<Content> contents = new ArrayList<>();

        private String authorName;

        private String messageId;

        private Message build() {
            return new Message(role, contents, authorName, messageId, Map.of());
        }
    }

    private static Content merge(Content existing, Content incoming) {
        if (existing instanceof TextContent left && incoming instanceof TextContent right) {
            return new TextContent(
                    left.text() + right.text(), CoreValidation.mergeStateMaps(left.metadata(), right.metadata()));
        }
        if (existing instanceof ReasoningContent left && incoming instanceof ReasoningContent right) {
            return mergeReasoning(left, right);
        }
        if (existing instanceof FunctionCallContent left && incoming instanceof FunctionCallContent right) {
            return mergeFunctionCall(left, right);
        }
        return null;
    }

    private static ReasoningContent mergeReasoning(ReasoningContent left, ReasoningContent right) {
        if (left.id() != null && right.id() != null && !left.id().equals(right.id())) {
            throw new ValidationException(
                    "Cannot merge reasoning content with different ids '" + left.id() + "' and '" + right.id() + "'.");
        }
        String text = concatenateNullable(left.text(), right.text());
        String protectedData = right.protectedData() != null ? right.protectedData() : left.protectedData();
        return new ReasoningContent(
                left.id() != null ? left.id() : right.id(),
                text,
                protectedData,
                CoreValidation.mergeStateMaps(left.metadata(), right.metadata()));
    }

    private static FunctionCallContent mergeFunctionCall(FunctionCallContent left, FunctionCallContent right) {
        if (!left.callId().equals(right.callId())) {
            throw new ValidationException("Cannot merge function calls with different callIds '"
                    + left.callId()
                    + "' and '"
                    + right.callId()
                    + "'.");
        }
        if (!left.name().equals(right.name())) {
            throw new ValidationException("Cannot merge function calls with different names '"
                    + left.name()
                    + "' and '"
                    + right.name()
                    + "'.");
        }
        return new FunctionCallContent(
                left.callId(),
                left.name(),
                mergeArguments(left.arguments(), right.arguments()),
                left.informationalOnly() || right.informationalOnly(),
                CoreValidation.mergeStateMaps(left.metadata(), right.metadata()));
    }

    private static StateValue mergeArguments(StateValue left, StateValue right) {
        if (left == StateValue.NullValue.INSTANCE) {
            return right;
        }
        if (right == StateValue.NullValue.INSTANCE) {
            return left;
        }
        if (left instanceof StateValue.StringValue leftString && right instanceof StateValue.StringValue rightString) {
            return StateValue.string(leftString.value() + rightString.value());
        }
        if (left instanceof StateValue.ObjectValue leftObject && right instanceof StateValue.ObjectValue rightObject) {
            return StateValue.object(CoreValidation.mergeStateMaps(leftObject.values(), rightObject.values()));
        }
        throw new ValidationException(
                "Function-call argument updates must both be strings, both be objects, or include null.");
    }

    private static String concatenateNullable(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }
}
