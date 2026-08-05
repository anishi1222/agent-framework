// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one immutable streaming update from an agent run.
 *
 * @param sequence optional non-negative source sequence
 * @param contents ordered content items
 * @param role optional author role
 * @param authorName optional author name
 * @param agentId optional stable agent identifier
 * @param responseId optional stable response identifier
 * @param messageId optional stable message correlation identifier
 * @param createdAt optional creation time
 * @param finishReason optional terminal finish reason
 * @param usage optional usage update
 * @param continuationToken optional JSON-shaped continuation token
 * @param metadata immutable update metadata
 */
public record AgentResponseUpdate(
        Long sequence,
        List<Content> contents,
        Role role,
        String authorName,
        String agentId,
        String responseId,
        String messageId,
        Instant createdAt,
        FinishReason finishReason,
        UsageDetails usage,
        StateValue continuationToken,
        Map<String, StateValue> metadata) {
    /** Creates and defensively copies an agent response update. */
    public AgentResponseUpdate {
        if (sequence != null && sequence < 0) {
            throw new ValidationException("sequence must not be negative.");
        }
        contents = CoreValidation.copyList(contents, "contents");
        authorName = CoreValidation.optionalNonBlank(authorName, "authorName");
        agentId = CoreValidation.optionalNonBlank(agentId, "agentId");
        responseId = CoreValidation.optionalNonBlank(responseId, "responseId");
        messageId = CoreValidation.optionalNonBlank(messageId, "messageId");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates a builder.
     *
     * @return update builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns concatenated text content in this update.
     *
     * @return text, never {@code null}
     */
    public String text() {
        return contents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }

    /** Builds an immutable {@link AgentResponseUpdate}. */
    public static final class Builder {
        private Long sequence;

        private List<? extends Content> contents = List.of();

        private Role role;

        private String authorName;

        private String agentId;

        private String responseId;

        private String messageId;

        private Instant createdAt;

        private FinishReason finishReason;

        private UsageDetails usage;

        private StateValue continuationToken;

        private Map<String, StateValue> metadata = Map.of();

        private Builder() {}

        /** Sets the source sequence. */
        public Builder sequence(long sequence) {
            this.sequence = sequence;
            return this;
        }

        /** Sets ordered content. */
        public Builder contents(List<? extends Content> contents) {
            this.contents = Objects.requireNonNull(contents, "contents");
            return this;
        }

        /** Sets the author role. */
        public Builder role(Role role) {
            this.role = Objects.requireNonNull(role, "role");
            return this;
        }

        /** Sets the author name. */
        public Builder authorName(String authorName) {
            this.authorName = authorName;
            return this;
        }

        /** Sets the stable agent identifier. */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /** Sets the stable response identifier. */
        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        /** Sets the stable message correlation identifier. */
        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        /** Sets the creation time. */
        public Builder createdAt(Instant createdAt) {
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            return this;
        }

        /** Sets the terminal finish reason. */
        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = Objects.requireNonNull(finishReason, "finishReason");
            return this;
        }

        /** Sets a usage update. */
        public Builder usage(UsageDetails usage) {
            this.usage = Objects.requireNonNull(usage, "usage");
            return this;
        }

        /** Sets an opaque JSON-shaped continuation token. */
        public Builder continuationToken(StateValue continuationToken) {
            this.continuationToken = Objects.requireNonNull(continuationToken, "continuationToken");
            return this;
        }

        /** Sets update metadata. */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates the immutable update.
         *
         * @return agent response update
         */
        public AgentResponseUpdate build() {
            return new AgentResponseUpdate(
                    sequence,
                    List.copyOf(contents),
                    role,
                    authorName,
                    agentId,
                    responseId,
                    messageId,
                    createdAt,
                    finishReason,
                    usage,
                    continuationToken,
                    metadata);
        }
    }
}
