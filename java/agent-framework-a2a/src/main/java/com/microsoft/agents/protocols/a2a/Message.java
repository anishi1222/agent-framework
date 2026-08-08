// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents one immutable A2A message.
 *
 * @param role author role
 * @param parts ordered non-empty parts
 * @param messageId creator-assigned message identifier
 * @param contextId optional conversation context
 * @param taskId optional continued task
 * @param referenceTaskIds related task identifiers
 * @param metadata immutable metadata
 * @param extensions extension URIs used by this message
 */
public record Message(
        Role role,
        List<Part> parts,
        String messageId,
        String contextId,
        String taskId,
        List<String> referenceTaskIds,
        Map<String, StateValue> metadata,
        List<URI> extensions)
        implements A2AStreamEvent, SendMessageResult {
    /** Creates an immutable, validated message. */
    public Message {
        role = java.util.Objects.requireNonNull(role, "role");
        if (role == Role.ROLE_UNSPECIFIED) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Outbound messages must declare ROLE_USER or ROLE_AGENT.");
        }
        parts = A2AValidation.nonEmptyList(parts, "parts");
        messageId = A2AValidation.nonBlank(messageId, "messageId");
        contextId = A2AValidation.optionalNonBlank(contextId, "contextId");
        taskId = A2AValidation.optionalNonBlank(taskId, "taskId");
        referenceTaskIds = A2AValidation.strings(referenceTaskIds, "referenceTaskIds", true);
        metadata = A2AValidation.metadata(metadata, "metadata");
        extensions = A2AValidation.list(extensions, "extensions").stream()
                .map(uri -> A2AValidation.absoluteUri(uri, "extension"))
                .toList();
    }

    /**
     * Creates a message builder with a generated identifier.
     *
     * @param role author role
     * @return builder
     */
    public static Builder builder(Role role) {
        return new Builder(role);
    }

    @Override
    public String kind() {
        return "message";
    }

    /** Builds an immutable {@link Message}. */
    public static final class Builder {
        private final Role role;
        private List<Part> parts = List.of();
        private String messageId = UUID.randomUUID().toString();
        private String contextId;
        private String taskId;
        private List<String> referenceTaskIds = List.of();
        private Map<String, StateValue> metadata = Map.of();
        private List<URI> extensions = List.of();

        private Builder(Role role) {
            this.role = role;
        }

        /** Sets ordered parts. */
        public Builder parts(List<? extends Part> values) {
            parts = List.copyOf(values);
            return this;
        }

        /** Sets the message identifier. */
        public Builder messageId(String value) {
            messageId = value;
            return this;
        }

        /** Sets the context identifier. */
        public Builder contextId(String value) {
            contextId = value;
            return this;
        }

        /** Sets the continued task identifier. */
        public Builder taskId(String value) {
            taskId = value;
            return this;
        }

        /** Sets related task identifiers. */
        public Builder referenceTaskIds(List<String> values) {
            referenceTaskIds = values;
            return this;
        }

        /** Sets message metadata. */
        public Builder metadata(Map<String, StateValue> values) {
            metadata = values;
            return this;
        }

        /** Sets extension URIs. */
        public Builder extensions(List<URI> values) {
            extensions = values;
            return this;
        }

        /** Creates the immutable message. */
        public Message build() {
            return new Message(role, parts, messageId, contextId, taskId, referenceTaskIds, metadata, extensions);
        }
    }
}
