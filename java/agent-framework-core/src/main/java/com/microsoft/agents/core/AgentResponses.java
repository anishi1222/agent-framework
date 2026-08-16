// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.Objects;

/** Converts and aggregates framework-owned agent response models. */
public final class AgentResponses {
    private AgentResponses() {}

    /**
     * Creates a chat response containing the fields shared with an agent response.
     *
     * <p>Agent identity and a structured value have no chat-response equivalents and are omitted.
     *
     * @param response agent response
     * @return immutable chat response
     */
    public static ChatResponse toChatResponse(AgentResponse<?> response) {
        Objects.requireNonNull(response, "response");
        return new ChatResponse(
                response.messages(),
                response.responseId(),
                null,
                null,
                response.createdAt(),
                response.finishReason(),
                response.usage(),
                response.continuationToken(),
                response.metadata(),
                response.updateSequences());
    }

    /**
     * Creates a chat update containing the fields shared with an agent update.
     *
     * <p>Agent identity has no chat-update equivalent and is omitted.
     *
     * @param update agent update
     * @return immutable chat update
     */
    public static ChatResponseUpdate toChatResponseUpdate(AgentResponseUpdate update) {
        Objects.requireNonNull(update, "update");
        return new ChatResponseUpdate(
                update.sequence(),
                update.contents(),
                update.role(),
                update.authorName(),
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

    /**
     * Aggregates ordered agent updates.
     *
     * @param updates ordered updates
     * @param <T> structured response value type
     * @return aggregated response
     */
    public static <T> AgentResponse<T> aggregate(Iterable<AgentResponseUpdate> updates) {
        return ResponseAggregator.aggregateAgent(updates);
    }
}
