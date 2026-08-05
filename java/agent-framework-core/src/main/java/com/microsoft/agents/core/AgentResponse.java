// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one immutable aggregated agent response.
 *
 * @param <T> optional structured response value type
 * @param messages ordered response messages
 * @param responseId optional stable response identifier
 * @param agentId optional stable agent identifier
 * @param createdAt optional creation time
 * @param finishReason optional finish reason
 * @param usage optional folded usage
 * @param value optional structured response value
 * @param continuationToken optional JSON-shaped continuation token
 * @param metadata immutable response metadata
 * @param updateSequences observed source sequences in arrival order
 */
public record AgentResponse<T>(
        List<Message> messages,
        String responseId,
        String agentId,
        Instant createdAt,
        FinishReason finishReason,
        UsageDetails usage,
        T value,
        StateValue continuationToken,
        Map<String, StateValue> metadata,
        List<Long> updateSequences) {
    /** Creates and defensively copies an agent response. */
    public AgentResponse {
        messages = CoreValidation.copyList(messages, "messages");
        responseId = CoreValidation.optionalNonBlank(responseId, "responseId");
        agentId = CoreValidation.optionalNonBlank(agentId, "agentId");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
        updateSequences = CoreValidation.copyList(updateSequences, "updateSequences");
    }

    /**
     * Returns concatenated message text.
     *
     * @return response text, never {@code null}
     */
    public String text() {
        return messages.stream().map(Message::text).reduce("", String::concat);
    }

    /**
     * Creates a builder.
     *
     * @param <T> structured response value type
     * @return response builder
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builds an immutable {@link AgentResponse}.
     *
     * @param <T> structured response value type
     */
    public static final class Builder<T> {
        private List<Message> messages = List.of();

        private String responseId;

        private String agentId;

        private Instant createdAt;

        private FinishReason finishReason;

        private UsageDetails usage;

        private T value;

        private StateValue continuationToken;

        private Map<String, StateValue> metadata = Map.of();

        private List<Long> updateSequences = List.of();

        private Builder() {}

        /** Sets ordered messages. */
        public Builder<T> messages(List<Message> messages) {
            this.messages = Objects.requireNonNull(messages, "messages");
            return this;
        }

        /** Sets the response identifier. */
        public Builder<T> responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        /** Sets the agent identifier. */
        public Builder<T> agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /** Sets the creation time. */
        public Builder<T> createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** Sets the finish reason. */
        public Builder<T> finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /** Sets folded usage. */
        public Builder<T> usage(UsageDetails usage) {
            this.usage = usage;
            return this;
        }

        /** Sets the optional structured value. */
        public Builder<T> value(T value) {
            this.value = value;
            return this;
        }

        /** Sets the continuation token. */
        public Builder<T> continuationToken(StateValue continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /** Sets response metadata. */
        public Builder<T> metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /** Sets observed update sequences. */
        public Builder<T> updateSequences(List<Long> updateSequences) {
            this.updateSequences = Objects.requireNonNull(updateSequences, "updateSequences");
            return this;
        }

        /**
         * Creates the immutable response.
         *
         * @return agent response
         */
        public AgentResponse<T> build() {
            return new AgentResponse<>(
                    messages,
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
    }
}
