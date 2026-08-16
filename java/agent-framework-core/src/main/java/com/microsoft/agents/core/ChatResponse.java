// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one immutable aggregated chat response.
 *
 * @param messages ordered response messages
 * @param responseId optional stable response identifier
 * @param conversationId optional stable conversation identifier
 * @param model optional model identifier
 * @param createdAt optional creation time
 * @param finishReason optional finish reason
 * @param usage optional folded usage
 * @param continuationToken optional JSON-shaped continuation token
 * @param metadata immutable response metadata
 * @param updateSequences observed source sequences in arrival order
 */
public record ChatResponse(
        List<Message> messages,
        String responseId,
        String conversationId,
        String model,
        Instant createdAt,
        FinishReason finishReason,
        UsageDetails usage,
        StateValue continuationToken,
        Map<String, StateValue> metadata,
        List<Long> updateSequences) {
    /** Creates and defensively copies a chat response. */
    public ChatResponse {
        messages = CoreValidation.copyList(messages, "messages");
        responseId = CoreValidation.optionalNonBlank(responseId, "responseId");
        conversationId = CoreValidation.optionalNonBlank(conversationId, "conversationId");
        model = CoreValidation.optionalNonBlank(model, "model");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
        updateSequences = CoreValidation.copyList(updateSequences, "updateSequences");
    }

    /**
     * Returns concatenated message text separated by line feeds.
     *
     * @return response text, never {@code null}
     */
    public String text() {
        return messages.stream()
                .map(Message::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .strip();
    }

    /**
     * Creates a builder.
     *
     * @return response builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds an immutable {@link ChatResponse}. */
    public static final class Builder {
        private List<Message> messages = List.of();

        private String responseId;

        private String conversationId;

        private String model;

        private Instant createdAt;

        private FinishReason finishReason;

        private UsageDetails usage;

        private StateValue continuationToken;

        private Map<String, StateValue> metadata = Map.of();

        private List<Long> updateSequences = List.of();

        private Builder() {}

        /** Sets ordered messages. */
        public Builder messages(List<Message> messages) {
            this.messages = Objects.requireNonNull(messages, "messages");
            return this;
        }

        /** Sets the response identifier. */
        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        /** Sets the conversation identifier. */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        /** Sets the model identifier. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Sets the creation time. */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** Sets the finish reason. */
        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /** Sets folded usage. */
        public Builder usage(UsageDetails usage) {
            this.usage = usage;
            return this;
        }

        /** Sets the continuation token. */
        public Builder continuationToken(StateValue continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /** Sets response metadata. */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /** Sets observed update sequences. */
        public Builder updateSequences(List<Long> updateSequences) {
            this.updateSequences = Objects.requireNonNull(updateSequences, "updateSequences");
            return this;
        }

        /**
         * Creates the immutable response.
         *
         * @return chat response
         */
        public ChatResponse build() {
            return new ChatResponse(
                    messages,
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
    }
}
