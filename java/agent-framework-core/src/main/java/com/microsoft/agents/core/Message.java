// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents one immutable provider-neutral chat message.
 *
 * @param role required author role
 * @param contents ordered immutable content items
 * @param authorName optional non-blank author name
 * @param messageId optional non-blank stable message identifier
 * @param metadata immutable framework metadata
 */
public record Message(
        Role role, List<Content> contents, String authorName, String messageId, Map<String, StateValue> metadata) {
    /** Creates and defensively copies a message. */
    public Message {
        Objects.requireNonNull(role, "role");
        contents = CoreValidation.copyList(contents, "contents");
        authorName = CoreValidation.optionalNonBlank(authorName, "authorName");
        messageId = CoreValidation.optionalNonBlank(messageId, "messageId");
        metadata = CoreValidation.copyStateMap(metadata, "metadata");
    }

    /**
     * Creates a message without optional properties.
     *
     * @param role required author role
     * @param contents ordered content items
     */
    public Message(Role role, List<? extends Content> contents) {
        this(role, List.copyOf(contents), null, null, Map.of());
    }

    /**
     * Creates a one-part text message.
     *
     * @param role required author role
     * @param text text, which may be empty
     * @return immutable message
     */
    public static Message text(Role role, String text) {
        return new Message(role, List.of(new TextContent(text)));
    }

    /**
     * Returns text content joined with one space and ignores non-text items.
     *
     * @return joined text, never {@code null}
     */
    public String text() {
        return contents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * Creates a builder.
     *
     * @param role required author role
     * @return message builder
     */
    public static Builder builder(Role role) {
        return new Builder(role);
    }

    /** Builds an immutable {@link Message}. */
    public static final class Builder {
        private final Role role;

        private List<? extends Content> contents = List.of();

        private String authorName;

        private String messageId;

        private Map<String, StateValue> metadata = Map.of();

        private Builder(Role role) {
            this.role = Objects.requireNonNull(role, "role");
        }

        /**
         * Sets ordered content items.
         *
         * @param contents content items
         * @return this builder
         */
        public Builder contents(List<? extends Content> contents) {
            this.contents = Objects.requireNonNull(contents, "contents");
            return this;
        }

        /**
         * Sets the optional author name.
         *
         * @param authorName author name
         * @return this builder
         */
        public Builder authorName(String authorName) {
            this.authorName = authorName;
            return this;
        }

        /**
         * Sets the stable message identifier.
         *
         * @param messageId message identifier
         * @return this builder
         */
        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        /**
         * Sets framework metadata.
         *
         * @param metadata metadata values
         * @return this builder
         */
        public Builder metadata(Map<String, StateValue> metadata) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /**
         * Creates the immutable message.
         *
         * @return message
         */
        public Message build() {
            return new Message(role, List.copyOf(contents), authorName, messageId, metadata);
        }
    }
}
